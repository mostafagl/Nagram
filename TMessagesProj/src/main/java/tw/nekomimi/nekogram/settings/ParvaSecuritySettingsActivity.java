package tw.nekomimi.nekogram.settings;

import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;

import tw.nekomimi.nekogram.helpers.ChatLock;
import tw.nekomimi.nekogram.helpers.ContactChanges;

/** Parvagram › Privacy & security: locked chats and the contact changes log. */
public class ParvaSecuritySettingsActivity extends ParvaPageActivity {

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.ParvaSecurityTitle);
    }

    @Override
    protected void buildRows() {
        header(R.string.ChatLockTitle);
        link("chatLockPassword", ChatLock.hasPassword() ? R.string.ChatLockChangePassword : R.string.ChatLockSetPassword, R.string.ChatLockPasswordInfo,
                () -> ChatLock.showSetPassword(this, () -> {
                    refresh();
                    showToast(R.string.ChatLockPasswordSaved);
                }));
        check("chatLockFingerprint", R.string.ChatLockFingerprint, R.string.ChatLockFingerprintInfo,
                ChatLock::useFingerprint, v -> ChatLock.setOption(ChatLock.OPTION_FINGERPRINT, v));
        check("chatLockNotifications", R.string.ChatLockHideNotifications, R.string.ChatLockHideNotificationsInfo,
                () -> ChatLock.getOption(ChatLock.OPTION_HIDE_NOTIFICATIONS, true), v -> ChatLock.setOption(ChatLock.OPTION_HIDE_NOTIFICATIONS, v));
        check("chatLockText", R.string.ChatLockHideText, R.string.ChatLockHideTextInfo,
                () -> ChatLock.getOption(ChatLock.OPTION_HIDE_TEXT, true), v -> {
                    ChatLock.setOption(ChatLock.OPTION_HIDE_TEXT, v);
                    getNotificationCenter().postNotificationName(org.telegram.messenger.NotificationCenter.dialogsNeedReload);
                });
        check("chatLockName", R.string.ChatLockHideName, R.string.ChatLockHideNameInfo,
                () -> ChatLock.getOption(ChatLock.OPTION_HIDE_NAME, false), v -> {
                    ChatLock.setOption(ChatLock.OPTION_HIDE_NAME, v);
                    getNotificationCenter().postNotificationName(org.telegram.messenger.NotificationCenter.dialogsNeedReload);
                });
        for (long dialogId : ChatLock.getLockedDialogs(currentAccount)) {
            final Row r = link(null, 0, 0, () -> askUnlockChat(dialogId));
            r.titleText = "🔒 " + dialogName(dialogId);
        }
        info(R.string.ChatLockHowTo);

        header(R.string.ContactChangesTitle);
        link("contactChanges", R.string.ContactChangesOpen, 0, () -> presentFragment(new ContactChangesActivity()))
                .titleText = unseenTitle();
        check("contactChangesEnabled", R.string.ContactChangesEnabled, R.string.ContactChangesEnabledInfo,
                ContactChanges::isEnabled, v -> {
                    ContactChanges.setEnabled(v);
                    if (v) {
                        ContactChanges.start(currentAccount);
                    }
                });
        choice("contactChangesWho", R.string.ContactChangesWho,
                () -> LocaleController.getString(ContactChanges.onlySelected() ? R.string.ContactChangesWhoSelected : R.string.ContactChangesWhoAll),
                () -> {
                    if (getParentActivity() == null) {
                        return;
                    }
                    final boolean only = ContactChanges.onlySelected();
                    showDialog(new AlertDialog.Builder(getParentActivity())
                            .setTitle(LocaleController.getString(R.string.ContactChangesWho))
                            .setItems(new CharSequence[]{
                                    (only ? "" : "✓ ") + LocaleController.getString(R.string.ContactChangesWhoAll),
                                    (only ? "✓ " : "") + LocaleController.getString(R.string.ContactChangesWhoSelected)
                            }, (d, which) -> {
                                ContactChanges.setOnlySelected(which == 1);
                                refresh();
                            })
                            .create());
                });
        if (ContactChanges.onlySelected()) {
            for (long userId : ContactChanges.getSelected(currentAccount)) {
                final Row r = link(null, 0, 0, () -> askRemoveWatched(userId));
                r.titleText = "👁 " + dialogName(userId);
            }
            link("contactChangesAdd", R.string.ContactChangesAdd, 0, this::pickContact);
        }
        check("contactChangesUnseenFirst", R.string.ContactChangesUnseenFirst, R.string.ContactChangesUnseenFirstInfo,
                ContactChanges::unseenFirst, ContactChanges::setUnseenFirst);
        for (int type : ContactChanges.TYPES) {
            check("contactChangeType" + type, ContactChangesActivity.typeName(type), 0,
                    () -> (ContactChanges.getTypes() & type) != 0,
                    v -> ContactChanges.setTypes(v ? ContactChanges.getTypes() | type : ContactChanges.getTypes() & ~type));
        }
        info(R.string.ContactChangesInfo);
    }

    private CharSequence unseenTitle() {
        final int unseen = ContactChanges.getInstance(currentAccount).getUnseenCount();
        final String title = LocaleController.getString(R.string.ContactChangesOpen);
        return unseen > 0 ? title + " (" + unseen + ")" : title;
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void showToast(int text) {
        if (getParentActivity() != null) {
            android.widget.Toast.makeText(getParentActivity(), LocaleController.getString(text), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private String dialogName(long dialogId) {
        if (DialogObject.isUserDialog(dialogId)) {
            final TLRPC.User user = getMessagesController().getUser(dialogId);
            if (user != null) {
                return ContactsController.formatName(user.first_name, user.last_name);
            }
        } else {
            final TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
            if (chat != null) {
                return chat.title;
            }
        }
        return String.valueOf(dialogId);
    }

    private void pickContact() {
        final android.os.Bundle args = new android.os.Bundle();
        args.putBoolean("onlyUsers", true);
        args.putBoolean("destroyAfterSelect", true);
        args.putBoolean("returnAsResult", true);
        args.putBoolean("needForwardCount", false);
        args.putBoolean("allowSelf", false);
        args.putBoolean("disableSections", true);
        final org.telegram.ui.ContactsActivity contacts = new org.telegram.ui.ContactsActivity(args);
        contacts.setDelegate((user, param, activity) -> {
            if (user != null) {
                ContactChanges.setSelected(currentAccount, user.id, true);
                refresh();
            }
        });
        presentFragment(contacts);
    }

    private void askRemoveWatched(long userId) {
        if (getParentActivity() == null) {
            return;
        }
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(dialogName(userId))
                .setMessage(LocaleController.getString(R.string.ContactChangesRemoveAsk))
                .setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> {
                    ContactChanges.setSelected(currentAccount, userId, false);
                    refresh();
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create());
    }

    private void askUnlockChat(long dialogId) {
        if (getParentActivity() == null) {
            return;
        }
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(dialogName(dialogId))
                .setMessage(LocaleController.getString(R.string.ChatLockRemoveAsk))
                .setPositiveButton(LocaleController.getString(R.string.ChatLockRemove), (d, w) ->
                        ChatLock.requestUnlock(this, currentAccount, dialogId, () -> {
                            ChatLock.setLocked(currentAccount, dialogId, false);
                            refresh();
                        }))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create());
    }
}
