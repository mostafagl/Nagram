package xyz.nextalone.nagram.history;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
 */
public final class MessageHistoryController {

    /** Hard cap per store so the database cannot grow without bound. */
    private static final long MAX_VERSIONS = 20000;
    private static final long MAX_DELETED = 20000;

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
     * Records a version for a message. {@code oldMessage} carries the content
     * before an edit (may be null when unavailable); {@code newMessage} is the
     * content after the edit. Duplicate content is ignored, so calling this
     * repeatedly for the same edit is safe.
     */
    public static void onMessageEdited(int account, MessageObject oldMessage, MessageObject newMessage) {
        if (!NaConfig.INSTANCE.getShowMessageEditHistory().Bool() || newMessage == null) {
            return;
        }
        final Snapshot oldSnap = oldMessage != null ? snapshot(account, oldMessage) : null;
        final Snapshot newSnap = snapshot(account, newMessage);
        if (newSnap == null) {
            return;
        }
        UIUtil.runOnIoDispatcher(() -> {
            try {
                if (oldSnap != null) {
                    storeVersion(oldSnap);
                }
                storeVersion(newSnap);
                trimVersions();
            } catch (Throwable ignore) {
            }
        });
    }

    /**
     * Records the last-known content of messages that were just deleted.
     * Messages whose content was never received are skipped by the caller.
     */
    public static void onMessagesDeleted(int account, List<MessageObject> messages) {
        if (!NaConfig.INSTANCE.getShowDeletedMessages().Bool() || messages == null || messages.isEmpty()) {
            return;
        }
        final List<Snapshot> snaps = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            final Snapshot snap = snapshot(account, messages.get(i));
            if (snap != null) {
                snaps.add(snap);
            }
        }
        if (snaps.isEmpty()) {
            return;
        }
        UIUtil.runOnIoDispatcher(() -> {
            try {
                for (Snapshot snap : snaps) {
                    storeDeleted(snap);
                }
                trimDeleted();
            } catch (Throwable ignore) {
            }
        });
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
        UIUtil.runOnIoDispatcher(() -> {
            try {
                versions().removeAll();
                deleted().removeAll();
            } catch (Throwable ignore) {
            }
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

    private static Snapshot snapshot(int account, MessageObject message) {
        if (message == null || message.messageOwner == null) {
            return null;
        }
        final TLRPC.Message owner = message.messageOwner;
        final Snapshot snap = new Snapshot();
        snap.account = account;
        snap.dialogId = message.getDialogId();
        snap.messageId = owner.id;
        snap.fromId = message.getSenderId();
        snap.outgoing = owner.out;
        snap.text = owner.message != null ? owner.message : "";
        snap.mediaType = mediaType(message);
        snap.mediaPath = localMediaPath(account, owner);
        snap.originalDate = owner.date;
        snap.editDate = owner.edit_date;
        snap.observedAt = System.currentTimeMillis();
        snap.contentHash = contentHash(snap.text, snap.mediaType, snap.editDate);
        // A message with neither text nor recognizable media carries nothing worth storing.
        if (TextUtils.isEmpty(snap.text) && TextUtils.isEmpty(snap.mediaType)) {
            return null;
        }
        return snap;
    }

    private static String mediaType(MessageObject message) {
        if (message.isVoice()) return "voice";
        if (message.isRoundVideo()) return "round";
        if (message.isVideo()) return "video";
        if (message.isGif()) return "gif";
        if (message.isPhoto()) return "photo";
        if (message.isMusic()) return "audio";
        if (message.isSticker()) return "sticker";
        if (message.isDocument()) return "document";
        return "";
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

    /** Stable 64-bit FNV-1a hash so dedup survives restarts. */
    private static long contentHash(String text, String mediaType, long editDate) {
        long hash = 0xcbf29ce484222325L;
        final String data = (text == null ? "" : text) + "\u0000" + (mediaType == null ? "" : mediaType) + "\u0000" + editDate;
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
