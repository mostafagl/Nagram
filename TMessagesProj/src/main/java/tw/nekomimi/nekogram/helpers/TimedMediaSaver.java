package tw.nekomimi.nekogram.helpers;

import android.app.Activity;
import android.content.Context;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.secretmedia.EncryptedFileInputStream;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Saves self-destructing and view-once media that "keep timed media" kept on this device to the
 * gallery (photos, videos, round videos) or Downloads (voice messages).
 *
 * Telegram downloads such media encrypted with a local key, so the regular "save to gallery" can't
 * use the file. A plain file is used when there is one; otherwise the encrypted copy is decrypted
 * into the app cache first; if neither is on the device it is downloaded (as a plain file, into the cache folder where timed media lives) and saved
 * when the download finishes. Nothing here marks the media viewed, so the sender's timer doesn't start.
 */
public final class TimedMediaSaver {

    private TimedMediaSaver() {
    }

    public static boolean canSave(MessageObject message) {
        return message != null && message.messageOwner != null && !message.isOut() && message.isTimedMedia()
                && (message.isPhoto() || message.isVideo() || message.isRoundVideo() || message.isVoice() || message.getDocument() != null)
                && !(MessageObject.getMedia(message.messageOwner).photo instanceof TLRPC.TL_photoEmpty)
                && !(MessageObject.getMedia(message.messageOwner).document instanceof TLRPC.TL_documentEmpty);
    }

    public static void save(Activity activity, MessageObject message) {
        if (activity == null || !canSave(message)) {
            return;
        }
        withFile(activity, message, file -> saveFile(activity, message, file));
    }

    /**
     * Calls {@code onReady} on the UI thread with a readable (not encrypted) copy of the message's
     * media: the downloaded file, a decrypted copy of one an older version stored encrypted, or the
     * file after downloading it. Shows a toast when that isn't possible.
     */
    public static void withFile(Context context, MessageObject message, Utilities.Callback<File> onReady) {
        withFile(context, message, onReady, false);
    }

    private static void withFile(Context context, MessageObject message, Utilities.Callback<File> onReady, boolean afterDownload) {
        final FileLoader loader = FileLoader.getInstance(message.currentAccount);
        final File plain = loader.getPathToMessage(message.messageOwner);
        // Older versions kept timed media encrypted in the cache folder.
        final File legacy = loader.getPathToMessage(message.messageOwner, true, false);
        if (plain == null || plain.getName().isEmpty()) {
            toast(context, R.string.TimedMediaSaveFailed);
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            File source = readableCopy(plain, plain);
            if (source == null && legacy != null && !legacy.getName().isEmpty()) {
                source = readableCopy(legacy, plain);
            }
            final File found = source;
            AndroidUtilities.runOnUIThread(() -> {
                if (found != null) {
                    onReady.run(found);
                } else if (afterDownload) {
                    toast(context, R.string.TimedMediaSaveFailed); // downloaded, yet not where expected: don't loop
                } else {
                    download(context, message, plain, onReady);
                }
            });
        });
    }

    /** {@code file} itself, or a decrypted copy of {@code file}.enc; null when neither is on the device. */
    private static File readableCopy(File file, File plain) {
        if (file.isFile() && file.length() > 0) {
            return file;
        }
        final File encrypted = new File(file.getAbsolutePath() + ".enc");
        final File key = new File(FileLoader.getInternalCacheDir(), encrypted.getName() + ".key");
        if (!encrypted.isFile() || !key.isFile()) {
            return null;
        }
        try {
            final File dir = new File(AndroidUtilities.getCacheDir(), "parva_timed");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return null;
            }
            final File out = new File(dir, plain.getName());
            if (out.isFile() && out.length() > 0) {
                return out;
            }
            try (InputStream in = new EncryptedFileInputStream(encrypted, key); FileOutputStream os = new FileOutputStream(out)) {
                final byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer, 0, buffer.length)) > 0) {
                    os.write(buffer, 0, read);
                }
            }
            return out.length() > 0 ? out : null;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static void download(Context context, MessageObject message, File plain, Utilities.Callback<File> onReady) {
        final int account = message.currentAccount;
        final String name = plain.getName();
        final NotificationCenter center = NotificationCenter.getInstance(account);
        final NotificationCenter.NotificationCenterDelegate[] holder = new NotificationCenter.NotificationCenterDelegate[1];
        holder[0] = (id, acc, args) -> {
            if (args.length == 0 || !(args[0] instanceof String)) {
                return;
            }
            final String loaded = (String) args[0];
            if (!name.equals(loaded) && !loaded.endsWith(name)) {
                return;
            }
            center.removeObserver(holder[0], NotificationCenter.fileLoaded);
            center.removeObserver(holder[0], NotificationCenter.fileLoadFailed);
            if (id == NotificationCenter.fileLoaded) {
                withFile(context, message, onReady, true);
            } else {
                toast(context, R.string.TimedMediaSaveFailed);
            }
        };
        center.addObserver(holder[0], NotificationCenter.fileLoaded);
        center.addObserver(holder[0], NotificationCenter.fileLoadFailed);

        // Same place as getPathToMessage: the regular media folder for kept timed media.
        final int cacheType = MessageObject.isKeptTimedIncoming(message.messageOwner) ? 0 : 1;
        final TLRPC.MessageMedia media = MessageObject.getMedia(message.messageOwner);
        final FileLoader loader = FileLoader.getInstance(account);
        if (media instanceof TLRPC.TL_messageMediaPhoto && media.photo != null) {
            final TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes, AndroidUtilities.getPhotoSize(true), false, null, true);
            if (size == null) {
                center.removeObserver(holder[0], NotificationCenter.fileLoaded);
                center.removeObserver(holder[0], NotificationCenter.fileLoadFailed);
                toast(context, R.string.TimedMediaSaveFailed);
                return;
            }
            loader.loadFile(ImageLocation.getForPhoto(size, media.photo), message, "jpg", FileLoader.PRIORITY_HIGH, cacheType);
        } else if (message.getDocument() != null) {
            loader.loadFile(message.getDocument(), message, FileLoader.PRIORITY_HIGH, cacheType);
        }
        toast(context, R.string.TimedMediaSaveDownloading);
    }

    private static void saveFile(Activity activity, MessageObject message, File file) {
        if (message.isVoice() || (message.getDocument() != null && !message.isVideo() && !message.isRoundVideo() && !message.isPhoto())) {
            final String mime = message.getDocument() != null ? message.getDocument().mime_type : "";
            String fileName = message.getDocumentName();
            if (fileName == null || fileName.isEmpty()) {
                fileName = file.getName();
            }
            MediaController.saveFile(file.getPath(), activity, 2, fileName, mime, uri -> toast(activity, R.string.TimedMediaSavedDownloads));
        } else {
            final int type = message.isVideo() || message.isRoundVideo() ? 1 : 0;
            MediaController.saveFile(file.getPath(), activity, type, null, null, uri -> toast(activity, R.string.TimedMediaSavedGallery));
        }
    }

    private static void toast(Context activity, int res) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                Toast.makeText(activity, LocaleController.getString(res), Toast.LENGTH_SHORT).show();
            } catch (Throwable ignore) {
            }
        });
    }
}
