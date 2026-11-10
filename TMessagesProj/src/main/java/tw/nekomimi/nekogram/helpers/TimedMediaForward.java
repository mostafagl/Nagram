package tw.nekomimi.nekogram.helpers;

import android.text.TextUtils;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * Telegram refuses to forward self-destructing or view-once media (the forward fails and the copy
 * shows a red "!"). With "keep timed media" on, such media received by the user is instead sent
 * again from the file on this device, as an ordinary photo, video or file without a timer.
 */
public final class TimedMediaForward {

    private TimedMediaForward() {
    }

    public static boolean isKeptTimed(MessageObject message) {
        return message != null && MessageObject.isKeptTimedIncoming(message.messageOwner) && TimedMediaSaver.canSave(message);
    }

    /**
     * Sends the kept timed media among {@code messages} to {@code peer} as new media and returns the
     * rest, which the caller forwards as usual. The list is returned unchanged when there are none.
     */
    public static ArrayList<MessageObject> sendKeptTimed(int account, ArrayList<MessageObject> messages, long peer, boolean hideCaption,
                                                         boolean notify, int scheduleDate, MessageObject replyToTopMsg) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        ArrayList<MessageObject> rest = null;
        for (int i = 0; i < messages.size(); i++) {
            final MessageObject message = messages.get(i);
            if (isKeptTimed(message)) {
                if (rest == null) {
                    rest = new ArrayList<>(messages.subList(0, i));
                }
                resend(account, message, peer, hideCaption, notify, scheduleDate, replyToTopMsg);
            } else if (rest != null) {
                rest.add(message);
            }
        }
        return rest != null ? rest : messages;
    }

    private static void resend(int account, MessageObject message, long peer, boolean hideCaption, boolean notify, int scheduleDate, MessageObject replyToTopMsg) {
        TimedMediaSaver.withFile(ApplicationLoader.applicationContext, message, file -> {
            final SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
            info.path = file.getAbsolutePath();
            info.isVideo = message.isVideo() || message.isRoundVideo();
            info.ttl = 0;
            if (!hideCaption && !TextUtils.isEmpty(message.messageOwner.message)) {
                info.caption = message.messageOwner.message;
                info.entities = message.messageOwner.entities;
            }
            final ArrayList<SendMessagesHelper.SendingMediaInfo> media = new ArrayList<>();
            media.add(info);
            // Photos and videos as such; anything else (voice, files) as a file.
            final boolean asDocument = !message.isPhoto() && !info.isVideo;
            SendMessagesHelper.prepareSendingMedia(AccountInstance.getInstance(account), media, peer, null, replyToTopMsg, null, null,
                    asDocument, false, null, notify, scheduleDate, 0, 0, false, null, null, 0, false, 0, 0, null);
        });
    }
}
