package tw.nekomimi.nekogram.helpers;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Approximate last seen for people who hide theirs ("last seen recently"), like AyuGram's local
 * last seen. Telegram itself notices when such a user is active (they send a message in any chat
 * you share, type, or read your messages) and shows them online for a moment; this keeps the
 * latest of those moments on this device and shows it after "last seen recently".
 */
public final class LocalLastSeen {

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
        if (userId <= 0 || date <= 0 || userId == UserConfig.getInstance(account).getClientUserId()) {
            return;
        }
        final String k = key(account, userId);
        final Integer old = get(k);
        if (old != null && old >= date) {
            return;
        }
        cache.put(k, date);
        prefs().edit().putInt(k, date).apply();
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
