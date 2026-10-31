package xyz.nextalone.nagram.history;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import tw.nekomimi.nekogram.utils.AlertUtil;
import tw.nekomimi.nekogram.utils.UIUtil;

/**
 * Lists the captured edit history and deleted messages. Each entry can be
 * opened to inspect the stored content; nothing here is sent to Telegram.
 */
public class MessageHistoryActivity extends BaseFragment {

    private RecyclerListView listView;
    private Adapter adapter;
    private TextView emptyView;
    private ActionBarMenuItem searchItem;

    private final List<Entry> allEntries = new ArrayList<>();
    private final List<Entry> filtered = new ArrayList<>();
    private String query = "";
    private boolean searchMode;

    private static final int MENU_SEARCH = 1;
    private static final int MENU_EXPORT = 2;
    private static final int MENU_CLEAR = 3;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        MessageHistoryController.init();
        return true;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.MessageHistory));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_EXPORT) {
                    exportHistory();
                } else if (id == MENU_CLEAR) {
                    confirmClear();
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        searchItem = menu.addItem(MENU_SEARCH, R.drawable.msg_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchExpand() {
                searchMode = true;
                query = "";
                applyFilter();
            }

            @Override
            public void onSearchCollapse() {
                searchMode = false;
                query = "";
                applyFilter();
            }

            @Override
            public void onTextChanged(EditText editText) {
                query = editText.getText().toString();
                applyFilter();
            }
        });
        searchItem.setSearchFieldHint(LocaleController.getString(R.string.Search));

        ActionBarMenuItem other = menu.addItem(0, R.drawable.ic_ab_other);
        other.addSubItem(MENU_EXPORT, R.drawable.msg_shareout, LocaleController.getString(R.string.MessageHistoryExport));
        other.addSubItem(MENU_CLEAR, R.drawable.msg_delete, LocaleController.getString(R.string.MessageHistoryClear));

        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frame;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        adapter = new Adapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < filtered.size()) {
                openEntry(filtered.get(position));
            }
        });
        frame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setTextSize(16);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setText(LocaleController.getString(R.string.MessageHistoryEmpty));
        emptyView.setVisibility(View.GONE);
        frame.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER, 24, 0, 24, 0));

        loadData();
        return fragmentView;
    }

    private void loadData() {
        UIUtil.runOnIoDispatcher(() -> {
            final List<Entry> entries = buildEntries();
            AndroidUtilities.runOnUIThread(() -> {
                allEntries.clear();
                allEntries.addAll(entries);
                applyFilter();
            });
        });
    }

    private List<Entry> buildEntries() {
        final List<Entry> result = new ArrayList<>();

        // Group version rows by (dialogId, messageId); only groups with real edits appear.
        final List<MessageVersionItem> versions = MessageHistoryController.getEditedMessages();
        final java.util.LinkedHashMap<String, List<MessageVersionItem>> groups = new java.util.LinkedHashMap<>();
        for (MessageVersionItem v : versions) {
            final String key = v.account + ":" + v.dialogId + ":" + v.messageId;
            List<MessageVersionItem> list = groups.get(key);
            if (list == null) {
                list = new ArrayList<>();
                groups.put(key, list);
            }
            list.add(v);
        }
        for (List<MessageVersionItem> list : groups.values()) {
            if (list.size() < 2) {
                continue;
            }
            Collections.sort(list, (a, b) -> Integer.compare(a.version, b.version));
            final Entry e = new Entry();
            e.deleted = false;
            e.versions = list;
            final MessageVersionItem last = list.get(list.size() - 1);
            e.account = last.account;
            e.dialogId = last.dialogId;
            e.messageId = last.messageId;
            e.fromId = last.fromId;
            e.text = last.text;
            e.mediaType = last.mediaType;
            e.time = last.observedAt;
            result.add(e);
        }

        for (DeletedMessageItem d : MessageHistoryController.getDeletedMessages()) {
            final Entry e = new Entry();
            e.deleted = true;
            e.deletedItem = d;
            e.account = d.account;
            e.dialogId = d.dialogId;
            e.messageId = d.messageId;
            e.fromId = d.fromId;
            e.text = d.text;
            e.mediaType = d.mediaType;
            e.time = d.observedAt;
            result.add(e);
        }

        Collections.sort(result, (a, b) -> Long.compare(b.time, a.time));
        return result;
    }

    private void applyFilter() {
        filtered.clear();
        final String q = query.trim().toLowerCase();
        for (Entry e : allEntries) {
            if (TextUtils.isEmpty(q)
                    || (e.text != null && e.text.toLowerCase().contains(q))
                    || displayName(e).toLowerCase().contains(q)) {
                filtered.add(e);
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (emptyView != null) {
            emptyView.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private String displayName(Entry e) {
        final MessagesController controller = MessagesController.getInstance(e.account);
        if (e.dialogId > 0) {
            final TLRPC.User user = controller.getUser(e.dialogId);
            if (user != null) {
                return UserObject.getUserName(user);
            }
        } else if (e.dialogId < 0) {
            final TLRPC.Chat chat = controller.getChat(-e.dialogId);
            if (chat != null) {
                return chat.title != null ? chat.title : "";
            }
        }
        return "#" + e.dialogId;
    }

    private String mediaLabel(String mediaType) {
        if (TextUtils.isEmpty(mediaType)) return "";
        switch (mediaType) {
            case "photo": return LocaleController.getString(R.string.AttachPhoto);
            case "video": return LocaleController.getString(R.string.AttachVideo);
            case "voice": return LocaleController.getString(R.string.AttachAudio);
            case "audio": return LocaleController.getString(R.string.AttachMusic);
            case "round": return LocaleController.getString(R.string.AttachRound);
            case "gif": return LocaleController.getString(R.string.AttachGif);
            case "sticker": return LocaleController.getString(R.string.AttachSticker);
            case "document": return LocaleController.getString(R.string.AttachDocument);
            default: return mediaType;
        }
    }

    private void openEntry(Entry e) {
        if (e.deleted) {
            openDeleted(e);
        } else {
            openVersions(e);
        }
    }

    private void openVersions(Entry e) {
        if (getParentActivity() == null) {
            return;
        }
        final Context context = getParentActivity();
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(8), AndroidUtilities.dp(22), AndroidUtilities.dp(8));

        for (MessageVersionItem v : e.versions) {
            final TextView header = new TextView(context);
            header.setTextColor(Theme.getColor(Theme.key_dialogTextBlue));
            header.setTextSize(13);
            header.setTypeface(AndroidUtilities.bold());
            String stamp = LocaleController.formatDateTime(v.observedAt / 1000, true);
            header.setText(LocaleController.formatString("MessageHistoryVersion", R.string.MessageHistoryVersion, v.version) + "  ·  " + stamp);
            content.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 2));

            final TextView body = new TextView(context);
            body.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            body.setTextSize(15);
            body.setTextIsSelectable(true);
            final String mediaLabel = mediaLabel(v.mediaType);
            final StringBuilder sb = new StringBuilder();
            if (!TextUtils.isEmpty(mediaLabel)) {
                sb.append("[").append(mediaLabel).append("]");
                if (!TextUtils.isEmpty(v.text)) sb.append("\n");
            }
            if (!TextUtils.isEmpty(v.text)) sb.append(v.text);
            body.setText(sb.toString());
            content.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        final android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(content);

        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(displayName(e));
        builder.setView(scroll);
        builder.setPositiveButton(LocaleController.getString(R.string.Close), null);
        builder.setNeutralButton(LocaleController.getString(R.string.Copy), (dialog, which) -> {
            final MessageVersionItem last = e.versions.get(e.versions.size() - 1);
            AndroidUtilities.addToClipboard(last.text);
            AlertUtil.showToast(LocaleController.getString(R.string.TextCopied));
        });
        showDialog(builder.create());
    }

    private void openDeleted(Entry e) {
        if (getParentActivity() == null) {
            return;
        }
        final Context context = getParentActivity();
        final DeletedMessageItem d = e.deletedItem;

        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(8), AndroidUtilities.dp(22), AndroidUtilities.dp(8));

        final TextView meta = new TextView(context);
        meta.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        meta.setTextSize(13);
        meta.setText(LocaleController.getString(R.string.MessageHistoryDeletedAt) + " "
                + LocaleController.formatDateTime(d.observedAt / 1000, true));
        content.addView(meta, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        final String mediaLabel = mediaLabel(d.mediaType);
        if (!TextUtils.isEmpty(mediaLabel)) {
            final TextView mt = new TextView(context);
            mt.setTextColor(Theme.getColor(Theme.key_dialogTextBlue));
            mt.setTextSize(14);
            mt.setText("[" + mediaLabel + "]");
            content.addView(mt, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));
        }

        if (!TextUtils.isEmpty(d.text)) {
            final TextView body = new TextView(context);
            body.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            body.setTextSize(15);
            body.setTextIsSelectable(true);
            body.setText(d.text);
            content.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        final android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(content);

        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(displayName(e));
        builder.setView(scroll);
        builder.setPositiveButton(LocaleController.getString(R.string.Close), null);
        final boolean hasFile = !TextUtils.isEmpty(d.mediaPath) && new java.io.File(d.mediaPath).exists();
        if (hasFile) {
            builder.setNeutralButton(LocaleController.getString(R.string.Open), (dialog, which) -> {
                final java.io.File file = new java.io.File(d.mediaPath);
                AndroidUtilities.openForView(file, file.getName(), null, getParentActivity(), null, false);
            });
        } else if (!TextUtils.isEmpty(d.text)) {
            builder.setNeutralButton(LocaleController.getString(R.string.Copy), (dialog, which) -> {
                AndroidUtilities.addToClipboard(d.text);
                AlertUtil.showToast(LocaleController.getString(R.string.TextCopied));
            });
        }
        showDialog(builder.create());
    }

    private void exportHistory() {
        UIUtil.runOnIoDispatcher(() -> {
            String path = null;
            try {
                final java.io.File dir = AndroidUtilities.getCacheDir();
                final java.io.File out = new java.io.File(dir, "message_history_" + System.currentTimeMillis() + ".json");
                final java.io.FileWriter writer = new java.io.FileWriter(out);
                writer.write(MessageHistoryController.exportJson());
                writer.close();
                path = out.getAbsolutePath();
            } catch (Throwable ignore) {
            }
            final String finalPath = path;
            AndroidUtilities.runOnUIThread(() -> {
                if (finalPath != null && getParentActivity() != null) {
                    final java.io.File file = new java.io.File(finalPath);
                    AndroidUtilities.openForView(file, file.getName(), "application/json", getParentActivity(), null, false);
                } else {
                    AlertUtil.showToast(LocaleController.getString(R.string.ErrorOccurred));
                }
            });
        });
    }

    private void confirmClear() {
        if (getParentActivity() == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.MessageHistoryClear));
        builder.setMessage(LocaleController.getString(R.string.MessageHistoryClearConfirm));
        builder.setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> {
            MessageHistoryController.clearAll();
            allEntries.clear();
            applyFilter();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        TextView button = (TextView) dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    private static final class Entry {
        boolean deleted;
        int account;
        long dialogId;
        long messageId;
        long fromId;
        String text;
        String mediaType;
        long time;
        List<MessageVersionItem> versions;
        DeletedMessageItem deletedItem;
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            return new RecyclerListView.Holder(new EntryCell(parent.getContext()));
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ((EntryCell) holder.itemView).bind(filtered.get(position), position < filtered.size() - 1);
        }

        @Override
        public int getItemCount() {
            return filtered.size();
        }
    }

    private class EntryCell extends FrameLayout {

        private final TextView titleView;
        private final TextView statusView;
        private final TextView previewView;
        private boolean needDivider;

        EntryCell(Context context) {
            super(context);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10), AndroidUtilities.dp(16), AndroidUtilities.dp(10));

            final LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);

            final LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);

            titleView = new TextView(context);
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            titleView.setTextSize(15);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setMaxLines(1);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(titleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            statusView = new TextView(context);
            statusView.setTextSize(12);
            statusView.setTypeface(AndroidUtilities.bold());
            row.addView(statusView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 0));

            column.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            previewView = new TextView(context);
            previewView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            previewView.setTextSize(14);
            previewView.setMaxLines(2);
            previewView.setEllipsize(TextUtils.TruncateAt.END);
            column.addView(previewView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

            addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        void bind(Entry e, boolean divider) {
            needDivider = divider;
            titleView.setText(displayName(e));
            if (e.deleted) {
                statusView.setTextColor(Theme.getColor(Theme.key_text_RedBold));
                statusView.setText(LocaleController.getString(R.string.MessageHistoryDeletedBadge));
            } else {
                statusView.setTextColor(Theme.getColor(Theme.key_dialogTextBlue));
                statusView.setText(LocaleController.formatString("MessageHistoryEditedBadge", R.string.MessageHistoryEditedBadge, e.versions.size()));
            }
            final String mediaLabel = mediaLabel(e.mediaType);
            final StringBuilder sb = new StringBuilder();
            if (!TextUtils.isEmpty(mediaLabel)) {
                sb.append("[").append(mediaLabel).append("] ");
            }
            if (!TextUtils.isEmpty(e.text)) {
                sb.append(e.text);
            }
            previewView.setText(sb.toString());
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            if (needDivider) {
                canvas.drawLine(AndroidUtilities.dp(16), getHeight() - 1, getWidth() - AndroidUtilities.dp(16), getHeight() - 1, Theme.dividerPaint);
            }
        }
    }
}
