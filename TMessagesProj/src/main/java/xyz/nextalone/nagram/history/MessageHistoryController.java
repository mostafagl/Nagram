package xyz.nextalone.nagram.history;

import android.text.TextUtils;
import androidx.collection.LongSparseArray;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import io.objectbox.Box;
import io.objectbox.BoxStore;
import io.objectbox.query.Query;
import tw.nekomimi.nekogram.utils.UIUtil;
import xyz.nextalone.nagram.NaConfig;

import static tw.nekomimi.nekogram.database.ObjectBoxKt.mkDatabase;

/**
 * Stores and queries the locally captured edit history and deleted-message
 * history. All content comes from messages the client had already received;
 * nothing is fetched or fabricated.
 *
 * Capture happens where updates are applied ({@link org.telegram.messenger.MessagesController}),
 * so it covers every chat, open or not, and works in the background. The previous text of an
 * edited message is read from the local database just before the edit is written; a message
 * that was never received before the edit has no earlier version to show. Messages deleted by
 * someone else stay in the chat (and in the local database) and are marked as deleted.
 */
public final class MessageHistoryController {

    /** Hard cap per store so the database cannot grow without bound. */
    private static final long MAX_VERSIONS = 20000;
    private static final long MAX_DELETED = 20000;

    public static final int FLAG_EDITED = 1;
    public static final int FLAG_DELETED = 2;

    /** (account, dialog, message) -> FLAG_* for messages with history, for the chat UI. */
    private static final ConcurrentHashMap<String, Integer> flags = new ConcurrentHashMap<>();
    private static volatile boolean indexLoaded;

    private static BoxStore db;
    private static Box<MessageVersionItem> versionBox;
    private static Box<DeletedMessageItem> deletedBox;

    private MessageHistoryController() {
    }

    public static synchronized void init() {
        if (db == null) {
            db = mkDatabase("message_history");
            versionBox = db.boxFor(MessageVersionItem.class);
            deletedBox = db.boxFor(DeletedMessageItem.class);
        }
    }

    private static String key(int account, long dialogId, long messageId) {
        return account + ":" + dialogId + ":" + messageId;
    }

    private static void addFlag(int account, long dialogId, long messageId, int flag) {
        final String key = key(account, dialogId, messageId);
        final Integer old = flags.get(key);
        flags.put(key, (old == null ? 0 : old) | flag);
    }

    /** Builds the in-memory index once, off the UI thread, then asks open chats to redraw. */
    private static void ensureIndex() {
        if (indexLoaded) {
            return;
        }
        indexLoaded = true;
        UIUtil.runOnIoDispatcher(() -> {
            try {
                final java.util.HashMap<String, Integer> versionCounts = new java.util.HashMap<>();
                for (MessageVersionItem item : versions().getAll()) {
                    final String key = key(item.account, item.dialogId, item.messageId);
                    final Integer count = versionCounts.get(key);
                    versionCounts.put(key, count == null ? 1 : count + 1);
                    if (count != null) {
                        addFlag(item.account, item.dialogId, item.messageId, FLAG_EDITED);
                    }
                }
                for (DeletedMessageItem item : deleted().getAll()) {
                    addFlag(item.account, item.dialogId, item.messageId, FLAG_DELETED);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            notifyChanged(-1);
        });
    }

    /** FLAG_EDITED and/or FLAG_DELETED for a message shown in a chat; 0 when it has no history. */
    public static int getFlags(int account, MessageObject message) {
        if (message == null || message.messageOwner == null || message.getId() <= 0 || message.scheduled) {
            return 0;
        }
        ensureIndex();
        if (flags.isEmpty()) {
            return 0;
        }
        final Integer value = flags.get(key(account, message.getDialogId(), message.getId()));
        return value == null ? 0 : value;
    }

    private static void notifyChanged(int account) {
        AndroidUtilities.runOnUIThread(() -> {
            if (account >= 0) {
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messageHistoryChanged);
            } else {
                for (int a = 0; a < org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    if (org.telegram.messenger.UserConfig.isValidAccount(a)) {
                        NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.messageHistoryChanged);
                    }
                }
            }
        });
    }

    public static boolean isKeepingDeletedMessages() {
        return NaConfig.INSTANCE.getShowDeletedMessages().Bool();
    }

    private static Box<MessageVersionItem> versions() {
        if (db == null) {
            init();
        }
        return versionBox;
    }

    private static Box<DeletedMessageItem> deleted() {
        if (db == null) {
            init();
        }
        return deletedBox;
    }

    // region capture

    /**
     * Called with incoming edits before they are written to the database. The write is queued on
     * the storage queue, so reading the stored copy here, on the same queue, sees the old text.
     */
    public static void onEditUpdates(int account, LongSparseArray<ArrayList<MessageObject>> editing) {
        if (!NaConfig.INSTANCE.getShowMessageEditHistory().Bool() || editing == null || editing.size() == 0) {
            return;
        }
        final MessagesStorage storage = MessagesStorage.getInstance(account);
        final ArrayList<TLRPC.Message> edited = new ArrayList<>();
        for (int a = 0; a < editing.size(); a++) {
            for (MessageObject object : editing.valueAt(a)) {
                if (object != null && object.messageOwner != null && !object.scheduled && object.getId() > 0) {
                    edited.add(object.messageOwner);
                }
            }
        }
        if (edited.isEmpty()) {
            return;
        }
        storage.getStorageQueue().postRunnable(() -> {
            final ArrayList<Snapshot[]> pairs = new ArrayList<>();
            for (TLRPC.Message newMessage : edited) {
                final long dialogId = MessageObject.getDialogId(newMessage);
                final TLRPC.Message oldMessage = storage.getStoredMessageInternal(dialogId, newMessage.id);
                final Snapshot newSnap = snapshot(account, newMessage);
                if (newSnap == null) {
                    continue;
                }
                final Snapshot oldSnap = oldMessage != null ? snapshot(account, oldMessage) : null;
                if (oldSnap != null && oldSnap.contentHash == newSnap.contentHash) {
                    continue; // reactions, views, buttons... not a change of content
                }
                pairs.add(new Snapshot[]{oldSnap, newSnap});
            }
            if (pairs.isEmpty()) {
                return;
            }
            UIUtil.runOnIoDispatcher(() -> {
                try {
                    for (Snapshot[] pair : pairs) {
                        if (pair[0] != null) {
                            storeVersion(pair[0]);
                        }
                        storeVersion(pair[1]);
                        if (pair[0] != null || countVersions(pair[1]) > 1) {
                            addFlag(account, pair[1].dialogId, pair[1].messageId, FLAG_EDITED);
                        }
                    }
                    trimVersions();
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                notifyChanged(account);
            });
        });
    }

    /**
     * Called with messages that someone deleted remotely. Their stored content is recorded and
     * the caller keeps them in the chat instead of deleting them; ids the client never received
     * are simply not found.
     */
    public static void onRemoteDeletion(int account, LongSparseArray<ArrayList<Integer>> deletedIds) {
        if (deletedIds == null || deletedIds.size() == 0) {
            return;
        }
        final MessagesStorage storage = MessagesStorage.getInstance(account);
        final long deletedAt = System.currentTimeMillis();
        for (int a = 0; a < deletedIds.size(); a++) {
            final long dialogId = deletedIds.keyAt(a);
            final ArrayList<Integer> ids = new ArrayList<>(deletedIds.valueAt(a));
            storage.getStorageQueue().postRunnable(() -> {
                final ArrayList<TLRPC.Message> stored = storage.getStoredMessagesInternal(dialogId, ids);
                final ArrayList<Snapshot> snaps = new ArrayList<>();
                for (TLRPC.Message message : stored) {
                    final Snapshot snap = snapshot(account, message);
                    if (snap != null) {
                        snap.observedAt = deletedAt;
                        snaps.add(snap);
                    }
                }
                if (snaps.isEmpty()) {
                    return;
                }
                for (Snapshot snap : snaps) {
                    addFlag(account, snap.dialogId, snap.messageId, FLAG_DELETED);
                }
                UIUtil.runOnIoDispatcher(() -> {
                    try {
                        for (Snapshot snap : snaps) {
                            storeDeleted(snap);
                            keepMediaCopy(snap);
                        }
                        trimDeleted();
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                });
                notifyChanged(account);
            });
        }
    }

    /** Folder for copies of deleted messages' media: Download/Parvagram/Saved Attachments. */
    public static File savedAttachmentsDir() {
        return new File(new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Parvagram"), "Saved Attachments");
    }

    /**
     * Copies the downloaded media of a deleted message out of the cache, so clearing the cache
     * doesn't lose it. Only media already on the device can be kept: once a message is deleted,
     * Telegram no longer serves its file.
     */
    private static void keepMediaCopy(Snapshot snap) {
        if (!tw.nekomimi.nekogram.NekoConfig.saveDeletedMedia || TextUtils.isEmpty(snap.mediaPath) || !isMediaKeptFor(snap.account, snap.dialogId)) {
            return;
        }
        try {
            final File source = new File(snap.mediaPath);
            if (!source.isFile() || source.length() == 0) {
                return;
            }
            final File dir = savedAttachmentsDir();
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            final File target = new File(dir, snap.dialogId + "_" + snap.messageId + "_" + source.getName());
            if (target.exists()) {
                return;
            }
            AndroidUtilities.copyFile(source, target);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static boolean isMediaKeptFor(int account, long dialogId) {
        final org.telegram.messenger.MessagesController controller = org.telegram.messenger.MessagesController.getInstance(account);
        if (dialogId > 0) {
            final TLRPC.User user = controller.getUser(dialogId);
            if (user != null && user.bot) {
                return tw.nekomimi.nekogram.NekoConfig.saveMediaBots;
            }
            return tw.nekomimi.nekogram.NekoConfig.saveMediaPrivate;
        }
        final TLRPC.Chat chat = controller.getChat(-dialogId);
        if (org.telegram.messenger.ChatObject.isChannelAndNotMegaGroup(chat)) {
            return tw.nekomimi.nekogram.NekoConfig.saveMediaChannels;
        }
        return tw.nekomimi.nekogram.NekoConfig.saveMediaGroups;
    }

    private static long countVersions(Snapshot snap) {
        final Query<MessageVersionItem> query = versions().query(
                MessageVersionItem_.dialogId.equal(snap.dialogId)
                        .and(MessageVersionItem_.messageId.equal(snap.messageId))
                        .and(MessageVersionItem_.account.equal(snap.account))).build();
        final long count = query.count();
        query.close();
        return count;
    }

    private static void storeVersion(Snapshot snap) {
        final Box<MessageVersionItem> box = versions();
        final Query<MessageVersionItem> query = box.query(
                MessageVersionItem_.dialogId.equal(snap.dialogId)
                        .and(MessageVersionItem_.messageId.equal(snap.messageId))
                        .and(MessageVersionItem_.account.equal(snap.account))).build();
        final List<MessageVersionItem> existing = query.find();
        query.close();
        int maxVersion = 0;
        for (MessageVersionItem item : existing) {
            if (item.contentHash == snap.contentHash) {
                return; // already stored this exact content
            }
            if (item.version > maxVersion) {
                maxVersion = item.version;
            }
        }
        final MessageVersionItem item = new MessageVersionItem();
        item.account = snap.account;
        item.dialogId = snap.dialogId;
        item.messageId = snap.messageId;
        item.fromId = snap.fromId;
        item.version = maxVersion + 1;
        item.text = snap.text;
        item.mediaType = snap.mediaType;
        item.editDate = snap.editDate;
        item.observedAt = snap.observedAt;
        item.contentHash = snap.contentHash;
        box.put(item);
    }

    private static void storeDeleted(Snapshot snap) {
        final Box<DeletedMessageItem> box = deleted();
        final Query<DeletedMessageItem> query = box.query(
                DeletedMessageItem_.dialogId.equal(snap.dialogId)
                        .and(DeletedMessageItem_.messageId.equal(snap.messageId))
                        .and(DeletedMessageItem_.account.equal(snap.account))).build();
        final DeletedMessageItem old = query.findFirst();
        query.close();
        if (old != null) {
            return; // already captured
        }
        final DeletedMessageItem item = new DeletedMessageItem();
        item.account = snap.account;
        item.dialogId = snap.dialogId;
        item.messageId = snap.messageId;
        item.fromId = snap.fromId;
        item.outgoing = snap.outgoing;
        item.text = snap.text;
        item.mediaType = snap.mediaType;
        item.mediaPath = snap.mediaPath;
        item.originalDate = snap.originalDate;
        item.observedAt = snap.observedAt;
        box.put(item);
    }

    private static void trimVersions() {
        final Box<MessageVersionItem> box = versions();
        if (box.count() <= MAX_VERSIONS) {
            return;
        }
        final Query<MessageVersionItem> query = box.query().order(MessageVersionItem_.observedAt).build();
        final List<MessageVersionItem> all = query.find();
        query.close();
        final long remove = box.count() - MAX_VERSIONS;
        for (int i = 0; i < remove && i < all.size(); i++) {
            box.remove(all.get(i));
        }
    }

    private static void trimDeleted() {
        final Box<DeletedMessageItem> box = deleted();
        if (box.count() <= MAX_DELETED) {
            return;
        }
        final Query<DeletedMessageItem> query = box.query().order(DeletedMessageItem_.observedAt).build();
        final List<DeletedMessageItem> all = query.find();
        query.close();
        final long remove = box.count() - MAX_DELETED;
        for (int i = 0; i < remove && i < all.size(); i++) {
            box.remove(all.get(i));
        }
    }

    // endregion

    // region queries

    public static List<MessageVersionItem> getVersions(long dialogId, long messageId) {
        return versions().query(
                        MessageVersionItem_.dialogId.equal(dialogId)
                                .and(MessageVersionItem_.messageId.equal(messageId)))
                .order(MessageVersionItem_.version)
                .build().find();
    }

    public static List<MessageVersionItem> getVersions(int account, long dialogId, long messageId) {
        final Query<MessageVersionItem> query = versions().query(
                        MessageVersionItem_.dialogId.equal(dialogId)
                                .and(MessageVersionItem_.messageId.equal(messageId))
                                .and(MessageVersionItem_.account.equal(account)))
                .order(MessageVersionItem_.version)
                .build();
        final List<MessageVersionItem> result = query.find();
        query.close();
        return result;
    }

    public static DeletedMessageItem getDeleted(int account, long dialogId, long messageId) {
        final Query<DeletedMessageItem> query = deleted().query(
                DeletedMessageItem_.dialogId.equal(dialogId)
                        .and(DeletedMessageItem_.messageId.equal(messageId))
                        .and(DeletedMessageItem_.account.equal(account))).build();
        final DeletedMessageItem item = query.findFirst();
        query.close();
        return item;
    }

    public static boolean hasVersions(long dialogId, long messageId) {
        final Query<MessageVersionItem> query = versions().query(
                MessageVersionItem_.dialogId.equal(dialogId)
                        .and(MessageVersionItem_.messageId.equal(messageId))).build();
        final long count = query.count();
        query.close();
        return count > 1;
    }

    public static List<MessageVersionItem> getEditedMessages() {
        return versions().query().orderDesc(MessageVersionItem_.observedAt).build().find();
    }

    public static List<DeletedMessageItem> getDeletedMessages() {
        return deleted().query().orderDesc(DeletedMessageItem_.observedAt).build().find();
    }

    public static void removeVersion(long id) {
        versions().remove(id);
    }

    public static void removeDeleted(long id) {
        deleted().remove(id);
    }

    public static void clearAll() {
        flags.clear();
        UIUtil.runOnIoDispatcher(() -> {
            try {
                versions().removeAll();
                deleted().removeAll();
            } catch (Throwable ignore) {
            }
            notifyChanged(-1);
        });
    }

    // endregion

    // region export

    public static String exportJson() {
        final JSONObject root = new JSONObject();
        try {
            final JSONArray edits = new JSONArray();
            for (MessageVersionItem item : getEditedMessages()) {
                final JSONObject o = new JSONObject();
                o.put("dialogId", item.dialogId);
                o.put("messageId", item.messageId);
                o.put("fromId", item.fromId);
                o.put("version", item.version);
                o.put("text", item.text);
                o.put("mediaType", item.mediaType);
                o.put("editDate", item.editDate);
                o.put("observedAt", item.observedAt);
                edits.put(o);
            }
            final JSONArray dels = new JSONArray();
            for (DeletedMessageItem item : getDeletedMessages()) {
                final JSONObject o = new JSONObject();
                o.put("dialogId", item.dialogId);
                o.put("messageId", item.messageId);
                o.put("fromId", item.fromId);
                o.put("outgoing", item.outgoing);
                o.put("text", item.text);
                o.put("mediaType", item.mediaType);
                o.put("originalDate", item.originalDate);
                o.put("observedAt", item.observedAt);
                dels.put(o);
            }
            root.put("editedMessages", edits);
            root.put("deletedMessages", dels);
        } catch (Throwable ignore) {
        }
        return root.toString();
    }

    // endregion

    // region snapshot extraction (runs on the caller's thread)

    private static Snapshot snapshot(int account, TLRPC.Message owner) {
        if (owner == null) {
            return null;
        }
        final Snapshot snap = new Snapshot();
        snap.account = account;
        snap.dialogId = MessageObject.getDialogId(owner);
        snap.messageId = owner.id;
        snap.fromId = MessageObject.getFromChatId(owner);
        snap.outgoing = owner.out;
        snap.text = owner.message != null ? owner.message : "";
        snap.mediaType = mediaType(owner);
        snap.mediaPath = localMediaPath(account, owner);
        snap.originalDate = owner.date;
        snap.editDate = owner.edit_date;
        snap.observedAt = System.currentTimeMillis();
        snap.contentHash = contentHash(snap.text, snap.mediaType, mediaId(owner));
        // A message with neither text nor recognizable media carries nothing worth storing.
        if (TextUtils.isEmpty(snap.text) && TextUtils.isEmpty(snap.mediaType)) {
            return null;
        }
        return snap;
    }

    private static String mediaType(TLRPC.Message message) {
        final TLRPC.MessageMedia media = message.media;
        if (media == null || media instanceof TLRPC.TL_messageMediaEmpty || media instanceof TLRPC.TL_messageMediaWebPage) {
            return "";
        }
        if (MessageObject.isVoiceMessage(message)) return "voice";
        if (MessageObject.isRoundVideoMessage(message)) return "round";
        if (MessageObject.isGifMessage(message)) return "gif";
        if (MessageObject.isVideoMessage(message)) return "video";
        if (media instanceof TLRPC.TL_messageMediaPhoto) return "photo";
        if (MessageObject.isMusicMessage(message)) return "audio";
        if (MessageObject.isStickerMessage(message)) return "sticker";
        if (media instanceof TLRPC.TL_messageMediaDocument) return "document";
        return "";
    }

    private static long mediaId(TLRPC.Message message) {
        final TLRPC.MessageMedia media = message.media;
        if (media == null) {
            return 0;
        }
        if (media.photo != null) {
            return media.photo.id;
        }
        if (media.document != null) {
            return media.document.id;
        }
        return 0;
    }

    private static String localMediaPath(int account, TLRPC.Message owner) {
        try {
            final File file = FileLoader.getInstance(account).getPathToMessage(owner);
            if (file != null && file.exists() && file.length() > 0) {
                return file.getAbsolutePath();
            }
        } catch (Throwable ignore) {
        }
        return "";
    }

    /** Stable 64-bit FNV-1a hash of what the reader sees, so dedup survives restarts. */
    private static long contentHash(String text, String mediaType, long mediaId) {
        long hash = 0xcbf29ce484222325L;
        final String data = (text == null ? "" : text) + "\u0000" + (mediaType == null ? "" : mediaType) + "\u0000" + mediaId;
        for (int i = 0; i < data.length(); i++) {
            hash ^= data.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static final class Snapshot {
        int account;
        long dialogId;
        long messageId;
        long fromId;
        boolean outgoing;
        String text;
        String mediaType;
        String mediaPath;
        long originalDate;
        long editDate;
        long observedAt;
        long contentHash;
    }

    // endregion
}
