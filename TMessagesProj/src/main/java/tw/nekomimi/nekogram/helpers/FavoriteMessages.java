package tw.nekomimi.nekogram.helpers;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Messages starred with the star button beside them, kept on this device only. The list stores
 * where each message is and a short preview, so it can be shown and opened without loading chats.
 */
public final class FavoriteMessages {

    public static final class Entry {
        public int account;
        public long dialogId;
        public int messageId;
        public String text;
        public int date;
        public long addedAt;

        String key() {
            return FavoriteMessages.key(account, dialogId, messageId);
        }
    }

    private static final String PREFS = "parva_favorites";
    private static ArrayList<Entry> entries;
    private static final HashSet<String> keys = new HashSet<>();

    private FavoriteMessages() {
    }

    private static String key(int account, long dialogId, int messageId) {
        return account + ":" + dialogId + ":" + messageId;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void load() {
        if (entries != null) {
            return;
        }
        entries = new ArrayList<>();
        keys.clear();
        try {
            final JSONArray array = new JSONArray(prefs().getString("list", "[]"));
            for (int i = 0; i < array.length(); i++) {
                final JSONObject o = array.getJSONObject(i);
                final Entry e = new Entry();
                e.account = o.optInt("a");
                e.dialogId = o.optLong("d");
                e.messageId = o.optInt("m");
                e.text = o.optString("t");
                e.date = o.optInt("ts");
                e.addedAt = o.optLong("at");
                entries.add(e);
                keys.add(e.key());
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void save() {
        try {
            final JSONArray array = new JSONArray();
            for (Entry e : entries) {
                array.put(new JSONObject().put("a", e.account).put("d", e.dialogId).put("m", e.messageId)
                        .put("t", e.text).put("ts", e.date).put("at", e.addedAt));
            }
            prefs().edit().putString("list", array.toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static synchronized boolean isFavorite(int account, MessageObject message) {
        if (message == null) {
            return false;
        }
        load();
        return keys.contains(key(account, message.getDialogId(), message.getId()));
    }

    /** Stars or unstars {@code message}; returns whether it is starred now. */
    public static synchronized boolean toggle(int account, MessageObject message) {
        load();
        final String k = key(account, message.getDialogId(), message.getId());
        if (keys.remove(k)) {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).key().equals(k)) {
                    entries.remove(i);
                    break;
                }
            }
            save();
            return false;
        }
        final Entry e = new Entry();
        e.account = account;
        e.dialogId = message.getDialogId();
        e.messageId = message.getId();
        CharSequence text = message.caption != null && message.caption.length() > 0 ? message.caption : message.messageText;
        e.text = text == null ? "" : text.toString();
        if (e.text.length() > 300) {
            e.text = e.text.substring(0, 300);
        }
        e.date = message.messageOwner != null ? message.messageOwner.date : 0;
        e.addedAt = System.currentTimeMillis();
        entries.add(0, e);
        keys.add(k);
        save();
        return true;
    }

    public static synchronized void remove(Entry entry) {
        load();
        entries.remove(entry);
        keys.remove(entry.key());
        save();
    }

    /** Starred messages of {@code account}, newest first. */
    public static synchronized ArrayList<Entry> get(int account) {
        load();
        final ArrayList<Entry> list = new ArrayList<>();
        for (Entry e : entries) {
            if (e.account == account) {
                list.add(e);
            }
        }
        return list;
    }

    public static String preview(Entry e) {
        return TextUtils.isEmpty(e.text) ? "…" : e.text.replace('\n', ' ');
    }
}
