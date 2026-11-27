package tw.nekomimi.nekogram.settings;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.StickerSetCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

import tw.nekomimi.nekogram.helpers.HiddenStickers;

/**
 * Parvagram › Chats › Hidden stickers & GIFs: every installed sticker pack with its preview (tap to
 * hide or show it in the panel) and previews of the hidden GIFs (tap one to show it again).
 */
public class HiddenStickersActivity extends BaseFragment {

    private static final int GIF_COLUMNS = 4;

    private LinearLayout content;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.HiddenStickersTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final ScrollView scrollView = new ScrollView(context);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        fragmentView = scrollView;
        rebuild();
        return fragmentView;
    }

    private void rebuild() {
        final Context context = getContext();
        if (content == null || context == null) {
            return;
        }
        content.removeAllViews();

        final TextInfoPrivacyCell howTo = new TextInfoPrivacyCell(context);
        howTo.setText(LocaleController.getString(R.string.HiddenStickersHowTo));
        content.addView(howTo);

        addHeader(context, R.string.HiddenStickersPacksHeader);
        final ArrayList<TLRPC.TL_messages_stickerSet> sets = new ArrayList<>(MediaDataController.getInstance(currentAccount).getStickerSets(MediaDataController.TYPE_IMAGE));
        final ArrayList<TLRPC.TL_messages_stickerSet> shown = new ArrayList<>();
        for (TLRPC.TL_messages_stickerSet pack : sets) {
            if (pack != null && pack.set != null && !pack.set.archived) {
                shown.add(pack);
            }
        }
        if (shown.isEmpty()) {
            addInfo(context, R.string.HiddenStickersNoPacks);
        }
        for (int i = 0; i < shown.size(); i++) {
            final TLRPC.TL_messages_stickerSet pack = shown.get(i);
            // option 3: a check mark on the right, shown while the pack is hidden
            final StickerSetCell cell = new StickerSetCell(context, 3);
            cell.setBackground(Theme.getSelectorDrawable(true));
            cell.setStickersSet(pack, i != shown.size() - 1);
            cell.setChecked(HiddenStickers.isPackHidden(pack.set.id), false);
            cell.setOnClickListener(v -> {
                final boolean hide = !HiddenStickers.isPackHidden(pack.set.id);
                HiddenStickers.setPackHidden(pack.set.id, hide);
                cell.setChecked(hide, true);
            });
            content.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        addInfo(context, R.string.HiddenStickersPacksInfo);

        addHeader(context, R.string.HiddenStickersGifsHeader);
        final ArrayList<TLRPC.Document> gifs = HiddenStickers.hiddenGifDocuments();
        final int withoutPreview = HiddenStickers.hiddenGifCount() - gifs.size();
        if (gifs.isEmpty() && withoutPreview <= 0) {
            addInfo(context, R.string.HiddenStickersNoGifs);
        } else {
            addGifGrid(context, gifs);
            if (withoutPreview > 0 || gifs.size() > 1) {
                final TextSettingsCell clear = new TextSettingsCell(context);
                clear.setBackground(Theme.getSelectorDrawable(true));
                clear.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
                clear.setText(LocaleController.formatString(R.string.HiddenStickersUnhideAllGifs, HiddenStickers.hiddenGifCount()), false);
                clear.setOnClickListener(v -> {
                    HiddenStickers.clearGifs();
                    rebuild();
                });
                content.addView(clear);
            }
        }
        addInfo(context, R.string.HiddenStickersGifsInfo);
    }

    private void addGifGrid(Context context, ArrayList<TLRPC.Document> gifs) {
        if (gifs.isEmpty()) {
            return;
        }
        final LinearLayout grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        grid.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
        LinearLayout row = null;
        for (int i = 0; i < gifs.size(); i++) {
            if (i % GIF_COLUMNS == 0) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 90));
            }
            final TLRPC.Document document = gifs.get(i);
            final FrameLayout tile = new FrameLayout(context);
            final BackupImageView image = new BackupImageView(context);
            image.setRoundRadius(AndroidUtilities.dp(6));
            final TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 320);
            // The animation itself, with its thumbnail while it loads.
            image.getImageReceiver().setAllowStartAnimation(true);
            image.getImageReceiver().setAutoRepeat(1);
            image.setImage(ImageLocation.getForDocument(document), "100_100", ImageLocation.getForDocument(thumb, document), "100_100_b", null, document.size, 1, document);
            tile.addView(image, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            final ImageView badge = new ImageView(context);
            badge.setImageResource(R.drawable.msg_archive_hide);
            badge.setScaleType(ImageView.ScaleType.CENTER);
            badge.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(22), 0x99000000));
            badge.setColorFilter(0xFFFFFFFF);
            tile.addView(badge, LayoutHelper.createFrame(22, 22, Gravity.TOP | Gravity.RIGHT, 0, 4, 4, 0));
            tile.setOnClickListener(v -> confirmUnhide(document));
            row.addView(tile, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, 3, 3, 3, 3));
        }
        // keep the last row's tiles the same width as the others
        final int rest = gifs.size() % GIF_COLUMNS;
        if (rest != 0 && row != null) {
            for (int i = rest; i < GIF_COLUMNS; i++) {
                row.addView(new View(context), LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, 3, 3, 3, 3));
            }
        }
        content.addView(grid, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private void confirmUnhide(TLRPC.Document document) {
        if (getParentActivity() == null) {
            return;
        }
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.HiddenStickersUnhideGif))
                .setMessage(LocaleController.getString(R.string.HiddenStickersUnhideGifConfirm))
                .setPositiveButton(LocaleController.getString(R.string.HiddenStickersUnhideButton), (d, w) -> {
                    HiddenStickers.setGifHidden(document, false);
                    rebuild();
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create());
    }

    private void addHeader(Context context, int text) {
        final HeaderCell header = new HeaderCell(context);
        header.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        header.setText(LocaleController.getString(text));
        content.addView(header);
    }

    private void addInfo(Context context, int text) {
        final TextInfoPrivacyCell info = new TextInfoPrivacyCell(context);
        info.setText(LocaleController.getString(text));
        content.addView(info);
    }
}
