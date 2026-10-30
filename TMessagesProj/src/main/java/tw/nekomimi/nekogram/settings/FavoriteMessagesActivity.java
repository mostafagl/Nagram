package tw.nekomimi.nekogram.settings;

import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

import tw.nekomimi.nekogram.helpers.FavoriteMessages;

/** Messages starred with the star button beside them; tap opens the message, long press removes it. */
public class FavoriteMessagesActivity extends BaseFragment {

    private ArrayList<FavoriteMessages.Entry> entries = new ArrayList<>();
    private RecyclerListView listView;
    private TextView emptyView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.FavoriteMessagesTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(new Adapter(context));
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= entries.size()) {
                return;
            }
            final FavoriteMessages.Entry e = entries.get(position);
            final Bundle args = new Bundle();
            if (DialogObject.isUserDialog(e.dialogId)) {
                args.putLong("user_id", e.dialogId);
            } else {
                args.putLong("chat_id", -e.dialogId);
            }
            args.putInt("message_id", e.messageId);
            presentFragment(new ChatActivity(args));
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (position < 0 || position >= entries.size()) {
                return false;
            }
            final FavoriteMessages.Entry e = entries.get(position);
            new AlertDialog.Builder(context)
                    .setTitle(LocaleController.getString(R.string.FavoriteMessagesTitle))
                    .setMessage(LocaleController.getString(R.string.FavoriteMessageRemoveAsk))
                    .setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> {
                        FavoriteMessages.remove(e);
                        reload();
                    })
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .show();
            return true;
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(AndroidUtilities.dp(32), 0, AndroidUtilities.dp(32), 0);
        emptyView.setText(LocaleController.getString(R.string.FavoriteMessagesEmpty));
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        reload();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        entries = FavoriteMessages.get(currentAccount);
        if (listView != null && listView.getAdapter() != null) {
            listView.getAdapter().notifyDataSetChanged();
        }
        if (emptyView != null) {
            emptyView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private String dialogName(long dialogId) {
        if (DialogObject.isUserDialog(dialogId)) {
            final TLRPC.User user = getMessagesController().getUser(dialogId);
            if (user != null) {
                return dialogId == getUserConfig().getClientUserId() ? LocaleController.getString(R.string.SavedMessages) : ContactsController.formatName(user.first_name, user.last_name);
            }
        } else {
            final TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
            if (chat != null) {
                return chat.title;
            }
        }
        return String.valueOf(dialogId);
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        Adapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final TextDetailSettingsCell cell = new TextDetailSettingsCell(context);
            cell.setMultilineDetail(true);
            cell.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final FavoriteMessages.Entry e = entries.get(position);
            String title = dialogName(e.dialogId);
            if (e.date != 0) {
                title += " · " + LocaleController.formatDateAudio(e.date, false);
            }
            ((TextDetailSettingsCell) holder.itemView).setTextAndValue(title, FavoriteMessages.preview(e), position < entries.size() - 1);
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }
    }
}
