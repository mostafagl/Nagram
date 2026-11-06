package tw.nekomimi.nekogram.helpers;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Sticker packs and GIFs the user chose to keep out of the sticker/GIF panel. They stay installed
 * and saved on Telegram; only this device's panel skips them. "Show hidden" (chat menu) brings them
 * back until it is turned off again or the app restarts.
 */
public final class HiddenStickers {

    private static HashSet<String> packs;
    private static HashSet<String> gifs;
    private static volatile boolean revealed;

    private HiddenStickers() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("parva_hidden_stickers", Context.MODE_PRIVATE);
    }

    private static synchronized HashSet<String> packs() {
        if (packs == null) {
            packs = new HashSet<>(prefs().getStringSet("packs", new HashSet<>()));
        }
        return packs;
    }

    private static synchronized HashSet<String> gifs() {
        if (gifs == null) {
            gifs = new HashSet<>(prefs().getStringSet("gifs", new HashSet<>()));
        }
        return gifs;
    }

    public static synchronized boolean hasAny() {
        return !packs().isEmpty() || !gifs().isEmpty();
    }

    public static synchronized int hiddenGifCount() {
        return gifs().size();
    }

    public static boolean isRevealed() {
        return revealed;
    }

    public static void setRevealed(boolean value) {
        revealed = value;
        notifyChanged();
    }

    public static synchronized boolean isPackHidden(long setId) {
        return setId != 0 && packs().contains(String.valueOf(setId));
    }

    public static synchronized void setPackHidden(long setId, boolean hide) {
        if (setId == 0) {
            return;
        }
        if (hide) {
            packs().add(String.valueOf(setId));
        } else {
            packs().remove(String.valueOf(setId));
        }
        prefs().edit().putStringSet("packs", new HashSet<>(packs())).apply();
        notifyChanged();
    }

    public static synchronized boolean isGifHidden(TLRPC.Document document) {
        return document != null && gifs().contains(String.valueOf(document.id));
    }

    public static synchronized void setGifHidden(TLRPC.Document document, boolean hide) {
        if (document == null) {
            return;
        }
        if (hide) {
            gifs().add(String.valueOf(document.id));
        } else {
            gifs().remove(String.valueOf(document.id));
        }
        prefs().edit().putStringSet("gifs", new HashSet<>(gifs())).apply();
        notifyChanged();
    }

    public static synchronized void clearGifs() {
        gifs().clear();
        prefs().edit().putStringSet("gifs", new HashSet<>()).apply();
        notifyChanged();
    }

    /** Whether the panel should currently skip this pack. */
    public static boolean shouldHidePack(TLRPC.StickerSet set) {
        return !revealed && set != null && isPackHidden(set.id);
    }

    /** Whether the panel should currently skip this sticker (it belongs to a hidden pack). */
    public static boolean shouldHideSticker(TLRPC.Document document) {
        return !revealed && document != null && isPackHidden(MediaDataController.getStickerSetId(document));
    }

    public static boolean shouldHideGif(TLRPC.Document document) {
        return !revealed && isGifHidden(document);
    }

    public static void filterStickers(ArrayList<TLRPC.Document> documents) {
        if (revealed || documents == null || packs().isEmpty()) {
            return;
        }
        for (int i = documents.size() - 1; i >= 0; i--) {
            if (shouldHideSticker(documents.get(i))) {
                documents.remove(i);
            }
        }
    }

    public static void filterGifs(ArrayList<TLRPC.Document> documents) {
        if (revealed || documents == null || gifs().isEmpty()) {
            return;
        }
        for (int i = documents.size() - 1; i >= 0; i--) {
            if (shouldHideGif(documents.get(i))) {
                documents.remove(i);
            }
        }
    }

    /** Makes open sticker/GIF panels reload their lists. */
    private static void notifyChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.isValidAccount(a)) {
                    NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.stickersDidLoad, MediaDataController.TYPE_IMAGE, true);
                    NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.recentDocumentsDidLoad, true, 0);
                    NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.recentDocumentsDidLoad, false, 0);
                }
            }
        });
    }
}
