package xyz.nextalone.nagram.font;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.RadioCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.List;

import tw.nekomimi.nekogram.helpers.AppRestartHelper;
import tw.nekomimi.nekogram.utils.AlertUtil;

/**
 * Picks the app font: shows a preview of a chat message in each font before it is applied,
 * imports TTF/OTF files and deletes imported ones (long press). The choice is saved at once and
 * fully applied after the app restarts, since text already on screen keeps its font.
 */
public class FontSettingsActivity extends BaseFragment {

    private static final int REQUEST_PICK_FONT = 7301;

    private LinearLayout listLayout;
    private TextView previewIncoming;
    private TextView previewOutgoing;
    private TextView previewName;
    private ButtonWithCounterView applyButton;
    private String candidate;

    @Override
    public View createView(Context context) {
        candidate = CustomFonts.getSelected();

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.CustomFontTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = root;

        final ScrollView scrollView = new ScrollView(context);
        root.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 0, 0, 72));

        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(content, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        content.addView(createPreview(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final HeaderCell header = new HeaderCell(context);
        header.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        header.setText(LocaleController.getString(R.string.CustomFontList));
        content.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        listLayout = new LinearLayout(context);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        listLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        content.addView(listLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextSettingsCell importCell = new TextSettingsCell(context);
        importCell.setBackground(Theme.getSelectorDrawable(true));
        importCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        importCell.setText(LocaleController.getString(R.string.CustomFontImport), false);
        importCell.setOnClickListener(v -> pickFont());
        content.addView(importCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextInfoPrivacyCell info = new TextInfoPrivacyCell(context);
        info.setText(LocaleController.getString(R.string.CustomFontInfo));
        content.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        applyButton = new ButtonWithCounterView(context, null).setRound();
        applyButton.setText(LocaleController.getString(R.string.CustomFontApply), false);
        applyButton.setOnClickListener(v -> apply());
        root.addView(applyButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.BOTTOM, 16, 0, 16, 12));

        rebuildList();
        updatePreview();
        return fragmentView;
    }

    private View createPreview(Context context) {
        final LinearLayout preview = new LinearLayout(context);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(14), AndroidUtilities.dp(14), AndroidUtilities.dp(14));
        preview.setBackgroundColor(Theme.getColor(Theme.key_chat_wallpaper));

        final LinearLayout incoming = bubble(context, false);
        previewName = new TextView(context);
        previewName.setTextSize(TypedValue.COMPLEX_UNIT_DIP, SharedConfig.fontSize - 2);
        previewName.setTextColor(Theme.getColor(Theme.key_chat_inForwardedNameText));
        previewName.setText(LocaleController.getString(R.string.CustomFontPreviewName));
        incoming.addView(previewName);
        previewIncoming = new TextView(context);
        previewIncoming.setTextSize(TypedValue.COMPLEX_UNIT_DIP, SharedConfig.fontSize);
        previewIncoming.setTextColor(Theme.getColor(Theme.key_chat_messageTextIn));
        previewIncoming.setText(LocaleController.getString(R.string.CustomFontPreviewIncoming));
        incoming.addView(previewIncoming);
        preview.addView(incoming, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 0, 48, 8));

        final LinearLayout outgoing = bubble(context, true);
        previewOutgoing = new TextView(context);
        previewOutgoing.setTextSize(TypedValue.COMPLEX_UNIT_DIP, SharedConfig.fontSize);
        previewOutgoing.setTextColor(Theme.getColor(Theme.key_chat_messageTextOut));
        previewOutgoing.setText(LocaleController.getString(R.string.CustomFontPreviewOutgoing));
        outgoing.addView(previewOutgoing);
        preview.addView(outgoing, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT, 48, 0, 0, 0));

        final ShadowSectionCell shadow = new ShadowSectionCell(context);
        final LinearLayout wrapper = new LinearLayout(context);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(preview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        wrapper.addView(shadow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return wrapper;
    }

    private LinearLayout bubble(Context context, boolean out) {
        final LinearLayout bubble = new LinearLayout(context);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(9));
        final GradientDrawable background = new GradientDrawable();
        background.setColor(Theme.getColor(out ? Theme.key_chat_outBubble : Theme.key_chat_inBubble));
        background.setCornerRadius(AndroidUtilities.dp(SharedConfig.bubbleRadius));
        bubble.setBackground(background);
        return bubble;
    }

    private void rebuildList() {
        listLayout.removeAllViews();
        final List<CustomFonts.FontItem> items = CustomFonts.list(LocaleController.getString(R.string.CustomFontSystem), LocaleController.getString(R.string.CustomFontVazirmatn));
        for (int i = 0; i < items.size(); i++) {
            final CustomFonts.FontItem item = items.get(i);
            final RadioCell cell = new RadioCell(getParentActivity());
            cell.setBackground(Theme.getSelectorDrawable(false));
            cell.setText(item.name, item.id.equals(candidate), i != items.size() - 1);
            final Typeface[] faces = CustomFonts.SYSTEM.equals(item.id) ? null : CustomFonts.load(item.id);
            if (faces != null) {
                // Each name is shown in its own font.
                setTypefaceDeep(cell, faces[0]);
            }
            cell.setOnClickListener(v -> {
                candidate = item.id;
                for (int a = 0; a < listLayout.getChildCount(); a++) {
                    ((RadioCell) listLayout.getChildAt(a)).setChecked(listLayout.getChildAt(a) == v, true);
                }
                updatePreview();
            });
            if (CustomFonts.isImported(item.id)) {
                cell.setOnLongClickListener(v -> {
                    confirmDelete(item);
                    return true;
                });
            }
            listLayout.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
    }

    private static void setTypefaceDeep(View view, Typeface typeface) {
        if (view instanceof TextView) {
            ((TextView) view).setTypeface(typeface);
        } else if (view instanceof android.view.ViewGroup) {
            final android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                setTypefaceDeep(group.getChildAt(i), typeface);
            }
        }
    }

    private void updatePreview() {
        final Typeface[] faces = CustomFonts.SYSTEM.equals(candidate) ? null : CustomFonts.load(candidate);
        final Typeface regular = faces != null ? faces[0] : Typeface.DEFAULT;
        final Typeface medium = faces != null ? faces[1] : AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM);
        previewIncoming.setTypeface(regular);
        previewOutgoing.setTypeface(regular);
        previewName.setTypeface(medium);
        final boolean changed = !candidate.equals(CustomFonts.getSelected());
        applyButton.setEnabled(changed);
        applyButton.setAlpha(changed ? 1f : .5f);
    }

    private void confirmDelete(CustomFonts.FontItem item) {
        if (getParentActivity() == null) {
            return;
        }
        final boolean wasSelected = item.id.equals(CustomFonts.getSelected());
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.CustomFontDelete));
        builder.setMessage(LocaleController.formatString(R.string.CustomFontDeleteText, item.name));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> {
            CustomFonts.delete(item.id);
            if (item.id.equals(candidate)) {
                candidate = CustomFonts.getSelected();
            }
            rebuildList();
            updatePreview();
            if (wasSelected) {
                askRestart();
            }
        });
        showDialog(builder.create());
    }

    private void pickFont() {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Font MIME types are reported inconsistently; filter by content when importing.
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf", "application/vnd.ms-opentype", "application/octet-stream"});
        try {
            startActivityForResult(intent, REQUEST_PICK_FONT);
        } catch (Exception e) {
            AlertUtil.showToast(e);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PICK_FONT || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        try {
            candidate = CustomFonts.importFont(getParentActivity(), data.getData());
        } catch (Exception e) {
            AlertUtil.showToast(LocaleController.formatString(R.string.CustomFontInvalid, e.getMessage()));
            return;
        }
        rebuildList();
        updatePreview();
    }

    private void apply() {
        CustomFonts.setSelected(candidate);
        AndroidUtilities.mediumTypeface = null;
        updatePreview();
        askRestart();
    }

    private void askRestart() {
        if (getParentActivity() == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.CustomFontTitle));
        builder.setMessage(LocaleController.getString(R.string.CustomFontRestart));
        builder.setPositiveButton(LocaleController.getString(R.string.RestartApp), (d, w) -> AppRestartHelper.triggerRebirth());
        builder.setNegativeButton(LocaleController.getString(R.string.VersionUpdateNotNow), null);
        showDialog(builder.create());
    }
}
