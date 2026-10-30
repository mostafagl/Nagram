package tw.nekomimi.nekogram.settings;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;

import tw.nekomimi.nekogram.NekoConfig;

/** Round buttons beside messages: history, mark timed media viewed, edit, forward without quote. */
public class MessageButtonsActivity extends BaseNekoSettingsActivity {

    private int headerRow;
    private int historyRow;
    private int timedViewRow;
    private int editRow;
    private int forwardRow;
    private int infoRow;

    /** {title, description} and row keys, for settings search. */
    public static final int[][] SEARCH_ROWS = {
            {R.string.MessageButtonHistory, R.string.MessageButtonHistoryInfo},
            {R.string.MessageButtonTimedView, R.string.MessageButtonTimedViewInfo},
            {R.string.MessageButtonEdit, R.string.MessageButtonEditInfo},
            {R.string.MessageButtonForward, R.string.MessageButtonForwardInfo},
    };
    public static final String[] SEARCH_KEYS = {"history", "timedView", "edit", "forwardNoQuote"};

    @Override
    protected void updateRows() {
        super.updateRows();
        headerRow = addRow();
        historyRow = addRow("history");
        timedViewRow = addRow("timedView");
        editRow = addRow("edit");
        forwardRow = addRow("forwardNoQuote");
        infoRow = addRow();
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        boolean value;
        if (position == historyRow) {
            NekoConfig.putBoolean("showHistoryButton", value = NekoConfig.showHistoryButton ^= true);
        } else if (position == timedViewRow) {
            NekoConfig.putBoolean("showTimedViewButton", value = NekoConfig.showTimedViewButton ^= true);
        } else if (position == editRow) {
            NekoConfig.putBoolean("showEditButton", value = NekoConfig.showEditButton ^= true);
        } else if (position == forwardRow) {
            NekoConfig.putBoolean("showNoQuoteForwardButton", value = NekoConfig.showNoQuoteForwardButton ^= true);
        } else {
            return;
        }
        ((TextCheckCell) view).setChecked(value);
        if (parentLayout != null) {
            parentLayout.rebuildAllFragmentViews(false, false); // open chats redraw their messages
        }
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.MessageButtonsTitle);
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    private class ListAdapter extends BaseListAdapter {

        ListAdapter(Context context) {
            super(context);
        }

        private void check(TextCheckCell cell, int title, int description, boolean checked, boolean divider) {
            cell.setTextAndValueAndCheck(LocaleController.getString(title), LocaleController.getString(description), checked, true, divider);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean payload, boolean divider) {
            switch (holder.getItemViewType()) {
                case TYPE_HEADER:
                    ((HeaderCell) holder.itemView).setText(LocaleController.getString(R.string.MessageButtonsTitle));
                    break;
                case TYPE_INFO_PRIVACY:
                    ((TextInfoPrivacyCell) holder.itemView).setText(LocaleController.getString(R.string.MessageButtonsInfo));
                    break;
                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (position == historyRow) {
                        check(cell, R.string.MessageButtonHistory, R.string.MessageButtonHistoryInfo, NekoConfig.showHistoryButton, divider);
                    } else if (position == timedViewRow) {
                        check(cell, R.string.MessageButtonTimedView, R.string.MessageButtonTimedViewInfo, NekoConfig.showTimedViewButton, divider);
                    } else if (position == editRow) {
                        check(cell, R.string.MessageButtonEdit, R.string.MessageButtonEditInfo, NekoConfig.showEditButton, divider);
                    } else if (position == forwardRow) {
                        check(cell, R.string.MessageButtonForward, R.string.MessageButtonForwardInfo, NekoConfig.showNoQuoteForwardButton, divider);
                    }
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == headerRow) {
                return TYPE_HEADER;
            }
            if (position == infoRow) {
                return TYPE_INFO_PRIVACY;
            }
            return TYPE_CHECK;
        }
    }
}
