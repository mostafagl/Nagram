package tw.nekomimi.nekogram.helpers;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.NotificationCenter;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Hidden chats: chats taken out of the chat list, folders, search, the forward picker and
 * notifications, reachable only through a hidden section that asks for the chat lock password
 * (or fingerprint). The section opens in the ways the user picks in settings: long-pressing the
 * app name in the chat list, long-pressing the menu button, long-pressing "Privacy & security" in
 * Parvagram's settings, or typing a secret code in the chat search.
 */
public final class HiddenChats {

    public static final int ENTRY_TITLE = 1;
    public static final int ENTRY_MENU_BUTTON = 2;
    public static final int ENTRY_SETTINGS_ROW = 4;
    public static final int ENTRY_SEARCH_CODE = 8;
    public static final int[] ENTRIES = {ENTRY_TITLE, ENTRY_MENU_BUTTON, ENTRY_SETTINGS_ROW, ENTRY_SEARCH_CODE};

    private static HashSet<String> hidden;

    private HiddenChats() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("parva_hidden_chats", Context.MODE_PRIVATE);
    }

    private static String key(int account, long dialogId) {
        return account + ":" + dialogId;
    }

    private static synchronized HashSet<String> set() {
        if (hidden == null) {
            hidden = new HashSet<>(prefs().getStringSet("dialogs", new HashSet<>()));
        }
        return hidden;
    }

    public static synchronized boolean hasAny() {
        return !set().isEmpty();
    }

    public static synchronized boolean isHidden(int account, long dialogId) {
        return dialogId != 0 && !set().isEmpty() && set().contains(key(account, dialogId));
    }

    public static synchronized void setHidden(int account, long dialogId, boolean hide) {
        if (hide) {
            set().add(key(account, dialogId));
        } else {
            set().remove(key(account, dialogId));
        }
        prefs().edit().putStringSet("dialogs", new HashSet<>(set())).apply();
        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogFiltersUpdated);
        });
    }

    public static synchronized ArrayList<Long> getHidden(int account) {
        final ArrayList<Long> list = new ArrayList<>();
        final String prefix = account + ":";
        for (String k : set()) {
            if (k.startsWith(prefix)) {
                try {
                    list.add(Long.parseLong(k.substring(prefix.length())));
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return list;
    }

    /** {@code dialogs} without the hidden chats (the same list when none are hidden). */
    public static ArrayList<TLRPC.Dialog> filter(int account, ArrayList<TLRPC.Dialog> dialogs) {
        if (dialogs == null || !hasAny()) {
            return dialogs;
        }
        boolean any = false;
        for (int i = 0; i < dialogs.size(); i++) {
            final TLRPC.Dialog d = dialogs.get(i);
            if (d != null && isHidden(account, d.id)) {
                any = true;
                break;
            }
        }
        if (!any) {
            return dialogs;
        }
        final ArrayList<TLRPC.Dialog> list = new ArrayList<>(dialogs.size());
        for (int i = 0; i < dialogs.size(); i++) {
            final TLRPC.Dialog d = dialogs.get(i);
            if (d == null || !isHidden(account, d.id)) {
                list.add(d);
            }
        }
        return list;
    }

    public static int getEntries() {
        return prefs().getInt("entries", ENTRY_TITLE | ENTRY_MENU_BUTTON | ENTRY_SETTINGS_ROW);
    }

    public static boolean isEntryEnabled(int entry) {
        return (getEntries() & entry) != 0;
    }

    public static void setEntryEnabled(int entry, boolean enabled) {
        prefs().edit().putInt("entries", enabled ? getEntries() | entry : getEntries() & ~entry).apply();
    }

    public static String getSearchCode() {
        return prefs().getString("code", "");
    }

    public static void setSearchCode(String code) {
        prefs().edit().putString("code", code == null ? "" : code.trim()).apply();
    }

    /** Whether {@code query}, typed in the chat search, is the secret code that opens hidden chats. */
    public static boolean isSearchCode(String query) {
        final String code = getSearchCode();
        return isEntryEnabled(ENTRY_SEARCH_CODE) && !TextUtils.isEmpty(code) && query != null
                && PersianDigits.toLatin(query.trim()).equals(PersianDigits.toLatin(code));
    }

    public static boolean hideNotifications(int account, long dialogId) {
        return prefs().getBoolean("hideNotifications", true) && isHidden(account, dialogId);
    }

    public static boolean getHideNotificationsOption() {
        return prefs().getBoolean("hideNotifications", true);
    }

    public static void setHideNotificationsOption(boolean value) {
        prefs().edit().putBoolean("hideNotifications", value).apply();
    }

    /** Opens the hidden chats after the password or fingerprint (setting a password first if needed). */
    public static void open(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final Runnable show = () -> fragment.presentFragment(new tw.nekomimi.nekogram.settings.HiddenChatsActivity());
        if (!ChatLock.hasPassword()) {
            ChatLock.showSetPassword(fragment, show);
        } else {
            ChatLock.authenticate(fragment, show);
        }
    }

    /** Hides {@code dialogId} from a chat's menu, setting a password first if there is none. */
    public static void hideFromChat(BaseFragment fragment, int account, long dialogId, Runnable onHidden) {
        final Runnable hide = () -> {
            setHidden(account, dialogId, true);
            if (onHidden != null) {
                onHidden.run();
            }
        };
        if (!ChatLock.hasPassword()) {
            ChatLock.showSetPassword(fragment, hide);
        } else {
            hide.run();
        }
    }

    /** Persian/Arabic digits to Latin, so a code typed with either keyboard matches. */
    static final class PersianDigits {
        static String toLatin(String s) {
            final StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                final char c = s.charAt(i);
                if (c >= '۰' && c <= '۹') {
                    sb.append((char) ('0' + (c - '۰')));
                } else if (c >= '٠' && c <= '٩') {
                    sb.append((char) ('0' + (c - '٠')));
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
    }
}
