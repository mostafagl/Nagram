package tw.nekomimi.nekogram.utils;
import org.telegram.tgnet.TLRPC;
import tw.nekomimi.nekogram.NekoConfig;

public class AyuGhostUtils {
    private static class AyuStateVariable {
        private final Object sync = new Object();
        public boolean val;
        public int resetAfter;

        public boolean process() {
            synchronized (sync) {
                if (resetAfter == -1) {
                    return val;
                }

                resetAfter -= 1;
                boolean currentVal = val;

                if (resetAfter == 0) {
                    val = false;
                }

                return currentVal;
            }
        }
    }

    private static final AyuStateVariable allowReadPacket = new AyuStateVariable();

    public static void setAllowReadPacket(boolean val, int resetAfter) {
        allowReadPacket.val = val;
        allowReadPacket.resetAfter = resetAfter;
    }

    public static boolean getAllowReadPacket() {
        return NekoConfig.sendReadMessagePackets || allowReadPacket.process();
    }

    private static final AyuStateVariable automaticallyScheduled = new AyuStateVariable();

    /** Whether the message just sent was scheduled by ghost mode (so the scheduled list isn't opened). */
    public static boolean getAutomaticallyScheduled() {
        return automaticallyScheduled.process();
    }

    /**
     * With "send as scheduled" on, a message is scheduled a few seconds ahead instead of sent
     * right away: the server then delivers it without marking the sender online. Telegram sends
     * anything scheduled less than 10 seconds ahead immediately, so the margin is a bit larger.
     */
    public static int applyScheduledSend(int account, long peer, int scheduleDate, boolean media) {
        if (!NekoConfig.useScheduledMessages || scheduleDate != 0 || peer == 0 || org.telegram.messenger.DialogObject.isEncryptedDialog(peer)) {
            return scheduleDate;
        }
        if (peer == org.telegram.messenger.UserConfig.getInstance(account).getClientUserId()) {
            return scheduleDate;
        }
        automaticallyScheduled.val = true;
        automaticallyScheduled.resetAfter = 1;
        return org.telegram.tgnet.ConnectionsManager.getInstance(account).getCurrentTime() + (media ? 26 : 11);
    }

    public static Long getDialogId(TLRPC.InputPeer peer) {
        long dialogId;
        if (peer.chat_id != 0) {
            dialogId = -peer.chat_id;
        } else if (peer.channel_id != 0) {
            dialogId = -peer.channel_id;
        } else {
            dialogId = peer.user_id;
        }

        return dialogId;
    }

    public static Long getDialogId(TLRPC.InputChannel peer) {
        return -peer.channel_id;
    }
}