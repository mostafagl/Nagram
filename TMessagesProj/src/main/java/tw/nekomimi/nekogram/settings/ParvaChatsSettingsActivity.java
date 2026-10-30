package tw.nekomimi.nekogram.settings;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ThemeActivity;

import tw.nekomimi.nekogram.NekoConfig;
import xyz.nextalone.nagram.NaConfig;

/** Parvagram › Chats & messages: everything Parvagram added for messages, the chat list and profiles. */
public class ParvaChatsSettingsActivity extends ParvaPageActivity {

    private static final int[] AVATAR_TAP_NAMES = {R.string.AvatarTapDefault, R.string.AvatarTapChat, R.string.AvatarTapProfile, R.string.AvatarTapPhoto};
    private static final int[] TAB_STYLE_NAMES = {R.string.TabStyleText, R.string.TabStyleIcon, R.string.TabStyleMix};
    private static final int[] ROUNDNESS_VALUES = {50, 30, 16, 0};
    private static final int[] ROUNDNESS_NAMES = {R.string.AvatarShapeCircle, R.string.AvatarShapeRounded, R.string.AvatarShapeSlightlyRounded, R.string.AvatarShapeSquare};

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.ParvaChatsTitle);
    }

    @Override
    protected void buildRows() {
        header(R.string.ParvaMessagesHeader);
        link("messageButtons", R.string.MessageButtonsTitle, R.string.MessageButtonsInfo, () -> presentFragment(new MessageButtonsActivity()));
        link("favorites", R.string.FavoriteMessagesTitle, R.string.FavoriteMessagesInfo, () -> presentFragment(new FavoriteMessagesActivity()));
        check("ShowMessageEditHistory", R.string.ShowMessageEditHistory, R.string.ShowMessageEditHistoryNotice,
                () -> NaConfig.INSTANCE.getShowMessageEditHistory().Bool(), v -> NaConfig.INSTANCE.getShowMessageEditHistory().setConfigBool(v));
        check("ShowDeletedMessages", R.string.ShowDeletedMessages, R.string.ShowDeletedMessagesNotice,
                () -> NaConfig.INSTANCE.getShowDeletedMessages().Bool(), v -> NaConfig.INSTANCE.getShowDeletedMessages().setConfigBool(v));
        link("MessageHistory", R.string.MessageHistory, R.string.MessageHistoryInfo, () -> presentFragment(new xyz.nextalone.nagram.history.MessageHistoryActivity()));
        check("AllowMediaInChatPreview", R.string.AllowMediaInChatPreview, R.string.AllowMediaInChatPreviewNotice,
                () -> NaConfig.INSTANCE.getAllowMediaInChatPreview().Bool(), v -> NaConfig.INSTANCE.getAllowMediaInChatPreview().setConfigBool(v));
        check("DontSendGreetingSticker", R.string.DontSendGreetingSticker, R.string.DontSendGreetingStickerInfo,
                () -> NekoConfig.dontSendGreetingSticker.Bool(), v -> NekoConfig.dontSendGreetingSticker.setConfigBool(v));
        shadow();

        header(R.string.ParvaChatListHeader);
        choice("avatarTap", R.string.AvatarTapTitle, () -> LocaleController.getString(AVATAR_TAP_NAMES[clamp(NekoConfig.avatarTapAction, AVATAR_TAP_NAMES.length)]),
                () -> pick(R.string.AvatarTapTitle, AVATAR_TAP_NAMES, NekoConfig.avatarTapAction, i -> {
                    NekoConfig.avatarTapAction = i;
                    NekoConfig.putInt("avatarTapAction", i);
                }));
        choice("TabTitleType", R.string.TabStyleTitle, () -> LocaleController.getString(TAB_STYLE_NAMES[clamp(NekoConfig.tabsTitleType.Int(), TAB_STYLE_NAMES.length)]),
                () -> pick(R.string.TabStyleTitle, TAB_STYLE_NAMES, NekoConfig.tabsTitleType.Int(), i -> {
                    NekoConfig.tabsTitleType.setConfigInt(i);
                    getNotificationCenter().postNotificationName(NotificationCenter.dialogFiltersUpdated);
                }));
        check("HideAllTab", R.string.ShowAllChatsTabSetting, R.string.ShowAllChatsTabSettingInfo,
                () -> !NekoConfig.hideAllTab.Bool(), v -> {
                    NekoConfig.hideAllTab.setConfigBool(!v);
                    getNotificationCenter().postNotificationName(NotificationCenter.dialogFiltersUpdated);
                });
        check("OpenArchiveOnPull", R.string.OpenArchiveOnPull, R.string.OpenArchiveOnPullInfo,
                () -> NekoConfig.openArchiveOnPull.Bool(), v -> {
                    NekoConfig.openArchiveOnPull.setConfigBool(v);
                    // Pulling opens the archive only while it is hidden above the list.
                    if (v && !SharedConfig.archiveHidden) {
                        SharedConfig.toggleArchiveHidden();
                        getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
                    }
                });
        shadow();

        header(R.string.ParvaAppearanceHeader);
        choice("avatarShape", R.string.AvatarShapeTitle, () -> LocaleController.getString(ROUNDNESS_NAMES[roundnessIndex()]),
                () -> pick(R.string.AvatarShapeTitle, ROUNDNESS_NAMES, roundnessIndex(), i -> {
                    NekoConfig.avatarRoundness = ROUNDNESS_VALUES[i];
                    NekoConfig.putInt("avatarRoundness", ROUNDNESS_VALUES[i]);
                    if (parentLayout != null) {
                        parentLayout.rebuildAllFragmentViews(false, false);
                    }
                }));
        link("bubbleCorners", R.string.BubbleCornersTitle, R.string.BubbleCornersInfo, () -> presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_BASIC)));
        link("font", R.string.CustomFontTitle, R.string.CustomFontRowInfo, () -> presentFragment(new xyz.nextalone.nagram.font.FontSettingsActivity()));
        shadow();

        header(R.string.ParvaProfilesHeader);
        check("ShowIdAndDc", R.string.ShowIdAndDc, R.string.ShowIdAndDcInfo,
                () -> NekoConfig.showIdAndDc.Bool(), v -> NekoConfig.showIdAndDc.setConfigBool(v));
        shadow();
    }

    private static int clamp(int i, int n) {
        return i < 0 || i >= n ? 0 : i;
    }

    private static int roundnessIndex() {
        for (int i = 0; i < ROUNDNESS_VALUES.length; i++) {
            if (NekoConfig.avatarRoundness >= ROUNDNESS_VALUES[i]) {
                return i;
            }
        }
        return ROUNDNESS_VALUES.length - 1;
    }

    interface IntSetter {
        void set(int value);
    }

    private void pick(int title, int[] names, int selected, IntSetter setter) {
        if (getParentActivity() == null) {
            return;
        }
        final CharSequence[] items = new CharSequence[names.length];
        for (int i = 0; i < names.length; i++) {
            items[i] = (i == selected ? "✓ " : "") + LocaleController.getString(names[i]);
        }
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(title))
                .setItems(items, (d, which) -> {
                    setter.set(which);
                    refresh();
                })
                .create());
    }
}
