package tw.nekomimi.nekogram.helpers;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;

import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import java.util.ArrayList;

/**
 * Approximate last seen for people who hide theirs ("last seen recently"), like AyuGram's local
 * last seen. Telegram itself notices when such a user is active (they send a message in any chat
 * you share, type, or read your messages) and shows them online for a moment; this keeps the
 * latest of those moments on this device and shows it after "last seen recently".
 */
public final class LocalLastSeen {
    public static final int OBSERVED = 0, MESSAGE = 1, TYPING = 2, READ = 3, ONLINE = 4, OFFLINE = 5;
    private static final int MAX_HISTORY = 500;

    public static final class Event {
        public long userId;
        public int source, date, fromMessageId, maxMessageId;
        public long observedAt;
    }

    private static final ConcurrentHashMap<String, Integer> cache = new ConcurrentHashMap<>();

    private LocalLastSeen() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("parva_last_seen", Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        return prefs().getBoolean("enabled", true);
    }

    public static void setEnabled(boolean enabled) {
        prefs().edit().putBoolean("enabled", enabled).apply();
    }

    private static String key(int account, long userId) {
        return account + ":" + userId;
    }

    public static void record(int account, long userId, int date) {
        record(account, userId, date, OBSERVED);
    }

    public static synchronized void record(int account, long userId, int date, int source) {
        if (!isEnabled()) return;
        if (userId <= 0 || date <= 0 || userId == UserConfig.getInstance(account).getClientUserId()) {
            return;
        }
        final String k = key(account, userId);
        final Integer old = get(k);
        if (old == null || old < date) {
            cache.put(k, date);
            prefs().edit().putInt(k, date).apply();
        }
        Event event = new Event();
        event.userId = userId;
        event.date = date;
        event.source = source;
        event.observedAt = System.currentTimeMillis();
        append(account, event);
    }

    /** A cumulative server watermark received by this device, not an exact read timestamp. */
    public static synchronized void recordRead(int account, long userId, int previousMax, int maxId, int date) {
        if (!isEnabled() || userId <= 0 || maxId <= previousMax || maxId <= 0
                || userId == UserConfig.getInstance(account).getClientUserId()) return;
        Event event = new Event();
        event.userId = userId;
        event.date = date;
        event.source = READ;
        event.fromMessageId = Math.max(1, previousMax + 1);
        event.maxMessageId = maxId;
        event.observedAt = System.currentTimeMillis();
        String k = key(account, userId);
        Integer latest = get(k);
        if (latest == null || latest < date) {
            cache.put(k, date);
            prefs().edit().putInt(k, date).apply();
        }
        append(account, event);
    }

    public static synchronized ArrayList<Event> getHistory(int account, long userId) {
        ArrayList<Event> events = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(prefs().getString("history_" + account, "[]"));
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                Event e = new Event();
                e.userId = o.getLong("u");
                e.source = o.getInt("s");
                e.date = o.getInt("d");
                e.observedAt = o.getLong("o");
                e.fromMessageId = o.optInt("f");
                e.maxMessageId = o.optInt("m");
                if (userId == 0 || userId == e.userId) events.add(e);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return events;
    }

    private static void append(int account, Event event) {
        ArrayList<Event> history = getHistory(account, 0);
        for (Event e : history) {
            if (e.userId == event.userId && e.source == TYPING && event.source == TYPING
                    && Math.abs((long) event.date - e.date) < 10) return;
            if (e.userId == event.userId && e.source == event.source && e.date == event.date
                    && e.maxMessageId == event.maxMessageId) return;
        }
        history.add(0, event);
        while (history.size() > MAX_HISTORY) history.remove(history.size() - 1);
        saveHistory(account, history);
    }

    private static void saveHistory(int account, ArrayList<Event> history) {
        try {
            JSONArray list = new JSONArray();
            for (Event e : history) list.put(new JSONObject().put("u", e.userId).put("s", e.source)
                    .put("d", e.date).put("o", e.observedAt).put("f", e.fromMessageId).put("m", e.maxMessageId));
            prefs().edit().putString("history_" + account, list.toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static synchronized void clearHistory(int account, long userId) {
        ArrayList<Event> history = getHistory(account, 0);
        history.removeIf(e -> userId == 0 || e.userId == userId);
        saveHistory(account, history);
    }

    public static synchronized void clearHistory(int account, long userId, boolean receipts) {
        ArrayList<Event> history = getHistory(account, 0);
        history.removeIf(e -> (userId == 0 || e.userId == userId) && (e.source == READ) == receipts);
        saveHistory(account, history);
    }

    public static synchronized void clearAccount(int account) {
        String prefix = account + ":";
        SharedPreferences.Editor editor = prefs().edit().remove("history_" + account);
        for (String k : prefs().getAll().keySet()) if (k.startsWith(prefix)) editor.remove(k);
        cache.keySet().removeIf(k -> k.startsWith(prefix));
        editor.apply();
    }

    private static Integer get(String k) {
        Integer value = cache.get(k);
        if (value == null) {
            final int stored = prefs().getInt(k, 0);
            if (stored == 0) {
                return null;
            }
            value = stored;
            cache.put(k, value);
        }
        return value;
    }

    /** The last moment {@code userId} was seen active by this device (unix time), or 0. */
    public static int getTime(int account, long userId) {
        final Integer value = get(key(account, userId));
        return value == null ? 0 : value;
    }

    /** "≈ last seen today at 14:32" in place of {@code hidden} ("last seen recently", etc.), when known. */
    public static String decorate(int account, long userId, String hidden) {
        if (!isEnabled()) {
            return hidden;
        }
        final int time = getTime(account, userId);
        if (time == 0) {
            return hidden;
        }
        return LocaleController.formatString(R.string.LocalLastSeenFormat, LocaleController.formatDateOnline(time, null));
    }
}
