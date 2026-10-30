package tw.nekomimi.nekogram.helpers;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

/**
 * Asks, once per account, whether to join the developer's channel. Nothing happens unless the
 * user taps "Join"; "Later" asks again after a few days (at most three times).
 */
public final class ChannelInvite {

    private static final long LATER_MS = 3L * 24 * 60 * 60 * 1000;
    private static final int MAX_ASKS = 3;
    private static boolean shownThisRun;

    private ChannelInvite() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("parva_channel_invite", Context.MODE_PRIVATE);
    }

    public static void maybeShow(BaseFragment fragment, int account) {
        if (shownThisRun || fragment == null || fragment.getParentActivity() == null || !UserConfig.getInstance(account).isClientActivated()) {
            return;
        }
        final SharedPreferences p = prefs();
        final String k = "acc" + UserConfig.getInstance(account).getClientUserId();
        if (p.getBoolean(k + "_done", false) || p.getInt(k + "_asks", 0) >= MAX_ASKS
                || System.currentTimeMillis() < p.getLong(k + "_next", 0)) {
            return;
        }
        shownThisRun = true;
        final String username = tw.nekomimi.nekogram.utils.UpdateUtil.channelUsername;
        MessagesController.getInstance(account).getUserNameResolver().resolve(username, peerId -> AndroidUtilities.runOnUIThread(() -> {
            if (peerId == null || peerId >= 0 || fragment.getParentActivity() == null) {
                return;
            }
            final TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-peerId);
            if (chat == null) {
                return;
            }
            if (!ChatObject.isNotInChat(chat)) {
                p.edit().putBoolean(k + "_done", true).apply(); // already a member
                return;
            }
            p.edit().putInt(k + "_asks", p.getInt(k + "_asks", 0) + 1).putLong(k + "_next", System.currentTimeMillis() + LATER_MS).apply();
            fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity())
                    .setTitle(LocaleController.getString(R.string.ChannelInviteTitle))
                    .setMessage(LocaleController.getString(R.string.ChannelInviteText))
                    .setPositiveButton(LocaleController.getString(R.string.ChannelInviteJoin), (d, w) -> {
                        p.edit().putBoolean(k + "_done", true).apply();
                        MessagesController.getInstance(account).addUserToChat(chat.id, UserConfig.getInstance(account).getCurrentUser(), 0, null, fragment, () ->
                                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, LocaleController.getString(R.string.ChannelInviteJoined)).show());
                    })
                    .setNegativeButton(LocaleController.getString(R.string.ChannelInviteLater), null)
                    .create());
        }));
    }
}
