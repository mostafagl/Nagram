package tw.nekomimi.nekogram.helpers;

import static org.telegram.ui.ProfileActivity.sendLogs;

import android.app.Activity;
import android.net.Uri;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.Map;

import tw.nekomimi.nekogram.ChatHistoryActivity;
import tw.nekomimi.nekogram.settings.BaseNekoSettingsActivity;
import tw.nekomimi.nekogram.settings.BaseNekoXSettingsActivity;
import tw.nekomimi.nekogram.settings.NekoChatSettingsActivity;
import tw.nekomimi.nekogram.settings.NekoAccountSettingsActivity;
import tw.nekomimi.nekogram.settings.NekoDebugSettingsActivity;
import tw.nekomimi.nekogram.settings.NekoEmojiSettingsActivity;
import tw.nekomimi.nekogram.settings.NekoExperimentalSettingsActivity;
import tw.nekomimi.nekogram.settings.NekoGeneralSettingsActivity;
import tw.nekomimi.nekogram.settings.NekoPasscodeSettingsActivity;
import tw.nekomimi.nekogram.settings.NekoSettingsActivity;
import xyz.nextalone.nagram.network.NetworkLogActivity;

public class SettingsHelper {

    public static void processDeepLink(Activity activity, Uri uri, Callback callback, Runnable unknown) {
        if (uri == null) {
            unknown.run();
            return;
        }
        var segments = uri.getPathSegments();
        if (segments.isEmpty() || segments.size() > 2 || !"nasettings".equals(segments.get(0))) {
            unknown.run();
            return;
        }
        BaseFragment fragment;
        BaseNekoSettingsActivity neko_fragment = null;
        BaseNekoXSettingsActivity nekox_fragment = null;
        if (segments.size() == 1) {
            fragment = new NekoSettingsActivity();
        } else if (PasscodeHelper.getSettingsKey().equals(segments.get(1))) {
            fragment = neko_fragment = new NekoPasscodeSettingsActivity();
        } else {
            switch (segments.get(1)) {
                case "a":
                case "account":
                    fragment = nekox_fragment = new NekoAccountSettingsActivity();
                    break;
                case "about":
                    fragment = neko_fragment = new NekoSettingsActivity();
                    break;
                case "chat":
                case "chats":
                case "c":
                    fragment = nekox_fragment = new NekoChatSettingsActivity();
                    break;
                case "debug":
                case "d":
                    fragment = nekox_fragment = new NekoDebugSettingsActivity();
                    break;
                case "experimental":
                case "e":
                    fragment = nekox_fragment = new NekoExperimentalSettingsActivity();
                    break;
                case "emoji":
                    fragment = neko_fragment = new NekoEmojiSettingsActivity();
                    break;
                case "general":
                case "g":
                    fragment = nekox_fragment = new NekoGeneralSettingsActivity();
                    break;
                case "network_logs":
                case "n":
                    fragment = new NetworkLogActivity();
                    break;
                case "recent_dialogs":
                case "r":
                    fragment = new ChatHistoryActivity();
                    break;
                case "send_logs":
                    sendLogs(activity, false);
                    return;
                default:
                    unknown.run();
                    return;
            }
        }
        callback.presentFragment(fragment);
        var row = uri.getQueryParameter("r");
        if (TextUtils.isEmpty(row)) {
            row = uri.getQueryParameter("row");
        }
        var value = uri.getQueryParameter("v");
        if (TextUtils.isEmpty(value)) {
            value = uri.getQueryParameter("value");
        }
        if (!TextUtils.isEmpty(row)) {
            var rowFinal = row;
            if (neko_fragment != null) {
                BaseNekoSettingsActivity finalNeko_fragment = neko_fragment;
                AndroidUtilities.runOnUIThread(() -> finalNeko_fragment.scrollToRow(rowFinal, unknown));
            } else if (nekox_fragment != null) {
                BaseNekoXSettingsActivity finalNekoX_fragment = nekox_fragment;
                if (!TextUtils.isEmpty(value)) {
                    String finalValue = value;
                    AndroidUtilities.runOnUIThread(() -> finalNekoX_fragment.importToRow(rowFinal, finalValue, unknown));
                } else {
                    AndroidUtilities.runOnUIThread(() -> finalNekoX_fragment.scrollToRow(rowFinal, unknown));
                }
            }
        }
    }

    public interface Callback {
        void presentFragment(BaseFragment fragment);
    }

    public static ArrayList<SettingsSearchResult> onCreateSearchArray(Callback callback) {
        ArrayList<SettingsSearchResult> items = new ArrayList<>();
        ArrayList<BaseNekoXSettingsActivity> fragments = new ArrayList<>();
        fragments.add(new NekoGeneralSettingsActivity());
        fragments.add(new NekoChatSettingsActivity());
        fragments.add(new NekoExperimentalSettingsActivity());
        fragments.add(new NekoDebugSettingsActivity());
        String n_title = LocaleController.getString(R.string.N_Config);
        for (BaseNekoXSettingsActivity fragment: fragments) {
            int uid = fragment.getBaseGuid();
            int drawable = fragment.getDrawable();
            String f_title = fragment.getTitle();
            for (Map.Entry<Integer, String> entry : fragment.getRowMapReverse().entrySet()) {
                Integer i = entry.getKey();
                String key = entry.getValue();
                if (key.equals(String.valueOf(i))) {
                    continue;
                }
                int guid = uid + i;
                String title = LocaleController.getString(key);
                if (title == null || title.isEmpty()) {
                    continue;
                }
                Runnable open = () -> {
                    try {
                        BaseNekoXSettingsActivity f = fragment.getClass().getDeclaredConstructor().newInstance();
                        callback.presentFragment(f);
                        AndroidUtilities.runOnUIThread(() -> f.scrollToRow(key, null));
                    } catch (Exception ignored) {}
                };
                SettingsSearchResult result = new SettingsSearchResult(
                        guid, title, n_title, f_title, drawable, open
                );
                items.add(result);
            }
        }
        // Ghost mode & spy (AyuGram's options).
        final String ghostTitle = LocaleController.getString(R.string.GhostAndSpyTitle);
        for (int i = 0; i < tw.nekomimi.nekogram.settings.NekoGhostModeActivity.SEARCH_KEYS.length; i++) {
            final String key = tw.nekomimi.nekogram.settings.NekoGhostModeActivity.SEARCH_KEYS[i];
            final int[] texts = tw.nekomimi.nekogram.settings.NekoGhostModeActivity.SEARCH_ROWS[i];
            final SettingsSearchResult result = new SettingsSearchResult(30000 + i, LocaleController.getString(texts[0]), n_title, ghostTitle, R.drawable.icon_ghost, () -> {
                final tw.nekomimi.nekogram.settings.NekoGhostModeActivity f = new tw.nekomimi.nekogram.settings.NekoGhostModeActivity();
                callback.presentFragment(f);
                AndroidUtilities.runOnUIThread(() -> f.scrollToRow(key, null), 300);
            });
            result.description = texts[1] != 0 ? LocaleController.getString(texts[1]) : null;
            items.add(result);
        }
        final String buttonsTitle = LocaleController.getString(R.string.MessageButtonsTitle);
        for (int i = 0; i < tw.nekomimi.nekogram.settings.MessageButtonsActivity.SEARCH_KEYS.length; i++) {
            final String key = tw.nekomimi.nekogram.settings.MessageButtonsActivity.SEARCH_KEYS[i];
            final int[] texts = tw.nekomimi.nekogram.settings.MessageButtonsActivity.SEARCH_ROWS[i];
            final SettingsSearchResult result = new SettingsSearchResult(30200 + i, LocaleController.getString(texts[0]), n_title, buttonsTitle, R.drawable.msg_forward_noquote, () -> {
                final tw.nekomimi.nekogram.settings.MessageButtonsActivity f = new tw.nekomimi.nekogram.settings.MessageButtonsActivity();
                callback.presentFragment(f);
                AndroidUtilities.runOnUIThread(() -> f.scrollToRow(key, null), 300);
            });
            result.description = LocaleController.getString(texts[1]);
            items.add(result);
        }
        // Parvagram's own pages.
        items.add(new SettingsSearchResult(30100, LocaleController.getString(R.string.ProxySettings), n_title, null, R.drawable.proxy_check,
                () -> callback.presentFragment(new org.telegram.ui.ProxyListActivity())));
        items.add(new SettingsSearchResult(30101, LocaleController.getString(R.string.V2RaySubscriptions), n_title, LocaleController.getString(R.string.ProxySettings), R.drawable.proxy_check,
                () -> callback.presentFragment(new org.telegram.ui.V2RaySubscriptionsActivity())));
        items.add(new SettingsSearchResult(30102, LocaleController.getString(R.string.CustomFontTitle), n_title, null, R.drawable.msg_photo_text_regular,
                () -> callback.presentFragment(new xyz.nextalone.nagram.font.FontSettingsActivity())));
        return items;
    }
}
