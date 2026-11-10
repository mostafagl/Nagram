package tw.nekomimi.nekogram.settings;

import android.text.InputType;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import tw.nekomimi.nekogram.helpers.ChatLock;
import tw.nekomimi.nekogram.helpers.HiddenChats;

/** Hidden chats settings: how the hidden section opens, its password and notifications. */
public class HiddenChatsSettingsActivity extends ParvaPageActivity {

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.HiddenChatsSettings);
    }

    @Override
    protected void buildRows() {
        header(R.string.HiddenChatsOpenWith);
        check("hiddenEntryTitle", R.string.HiddenChatsEntryTitle, R.string.HiddenChatsEntryTitleInfo,
                () -> HiddenChats.isEntryEnabled(HiddenChats.ENTRY_TITLE), v -> HiddenChats.setEntryEnabled(HiddenChats.ENTRY_TITLE, v));
        check("hiddenEntryMenu", R.string.HiddenChatsEntryMenu, R.string.HiddenChatsEntryMenuInfo,
                () -> HiddenChats.isEntryEnabled(HiddenChats.ENTRY_MENU_BUTTON), v -> HiddenChats.setEntryEnabled(HiddenChats.ENTRY_MENU_BUTTON, v));
        check("hiddenEntrySettings", R.string.HiddenChatsEntrySettings, R.string.HiddenChatsEntrySettingsInfo,
                () -> HiddenChats.isEntryEnabled(HiddenChats.ENTRY_SETTINGS_ROW), v -> HiddenChats.setEntryEnabled(HiddenChats.ENTRY_SETTINGS_ROW, v));
        check("hiddenEntryCode", R.string.HiddenChatsEntryCode, R.string.HiddenChatsEntryCodeInfo,
                () -> HiddenChats.isEntryEnabled(HiddenChats.ENTRY_SEARCH_CODE), v -> {
                    HiddenChats.setEntryEnabled(HiddenChats.ENTRY_SEARCH_CODE, v);
                    if (v && TextUtils.isEmpty(HiddenChats.getSearchCode())) {
                        askCode();
                    }
                    refresh();
                });
        if (HiddenChats.isEntryEnabled(HiddenChats.ENTRY_SEARCH_CODE)) {
            choice("hiddenCode", R.string.HiddenChatsCode,
                    () -> TextUtils.isEmpty(HiddenChats.getSearchCode()) ? LocaleController.getString(R.string.HiddenChatsCodeNotSet) : "••••",
                    this::askCode);
        }
        info(R.string.HiddenChatsOpenInfo);

        header(R.string.HiddenChatsSecurity);
        link("hiddenPassword", ChatLock.hasPassword() ? R.string.ChatLockChangePassword : R.string.ChatLockSetPassword, R.string.HiddenChatsPasswordInfo,
                () -> ChatLock.showSetPassword(this, this::refresh));
        check("hiddenFingerprint", R.string.ChatLockFingerprint, R.string.ChatLockFingerprintInfo,
                ChatLock::useFingerprint, v -> ChatLock.setOption(ChatLock.OPTION_FINGERPRINT, v));
        check("hiddenNotifications", R.string.ChatLockHideNotifications, R.string.HiddenChatsNotificationsInfo,
                HiddenChats::getHideNotificationsOption, HiddenChats::setHideNotificationsOption);
        info(R.string.HiddenChatsHowTo);
    }

    private void askCode() {
        if (getParentActivity() == null) {
            return;
        }
        final EditText field = new EditText(getParentActivity());
        field.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 18);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        field.setHint(LocaleController.getString(R.string.HiddenChatsCodeHint));
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        field.setSingleLine(true);
        final LinearLayout layout = new LinearLayout(getParentActivity());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 8, 24, 8));
        final AlertDialog dialog = new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.HiddenChatsCode))
                .setMessage(LocaleController.getString(R.string.HiddenChatsCodeInfo))
                .setView(layout)
                .setPositiveButton(LocaleController.getString(R.string.Save), null)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        showDialog(dialog);
        final android.view.View save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (save == null) {
            return;
        }
        save.setOnClickListener(v -> {
            final String code = field.getText().toString().trim();
            if (code.length() < 4) {
                AndroidUtilities.shakeViewSpring(field, -6);
                Toast.makeText(getParentActivity(), LocaleController.getString(R.string.HiddenChatsCodeShort), Toast.LENGTH_SHORT).show();
                return;
            }
            HiddenChats.setSearchCode(code);
            AndroidUtilities.hideKeyboard(field);
            dialog.dismiss();
            refresh();
        });
    }
}
