package tw.nekomimi.nekogram.settings;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

import tw.nekomimi.nekogram.helpers.HiddenStickers;

/** Parvagram › Chats › Hidden stickers & GIFs: pick the sticker packs the panel leaves out. */
public class HiddenStickersActivity extends ParvaPageActivity {

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.HiddenStickersTitle);
    }

    @Override
    protected void buildRows() {
        info(R.string.HiddenStickersHowTo);

        header(R.string.HiddenStickersPacksHeader);
        final ArrayList<TLRPC.TL_messages_stickerSet> sets = new ArrayList<>(MediaDataController.getInstance(currentAccount).getStickerSets(MediaDataController.TYPE_IMAGE));
        boolean any = false;
        for (TLRPC.TL_messages_stickerSet pack : sets) {
            if (pack == null || pack.set == null || pack.set.archived) {
                continue;
            }
            final long id = pack.set.id;
            final Row row = check(null, 0, 0, () -> HiddenStickers.isPackHidden(id), v -> HiddenStickers.setPackHidden(id, v));
            row.titleText = pack.set.title;
            any = true;
        }
        if (!any) {
            info(R.string.HiddenStickersNoPacks);
        } else {
            shadow();
        }

        header(R.string.HiddenStickersGifsHeader);
        choice(null, R.string.HiddenStickersGifsCount, () -> String.valueOf(HiddenStickers.hiddenGifCount()), () -> {
            if (HiddenStickers.hiddenGifCount() > 0) {
                HiddenStickers.clearGifs();
                refresh();
            }
        });
        info(R.string.HiddenStickersGifsInfo);
    }
}
