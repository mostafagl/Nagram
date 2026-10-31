package tw.nekomimi.nekogram.settings;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
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
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
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

import tw.nekomimi.nekogram.helpers.HiddenChats;

/** The hidden chats; tap opens one, long press takes it out of hiding. The gear opens the settings. */
public class HiddenChatsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private ArrayList<Long> dialogs = new ArrayList<>();
    private RecyclerListView listView;
    private TextView emptyView;

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.dialogsNeedReload);
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.dialogsNeedReload);
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        reload();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.HiddenChatsTitle));
        actionBar.createMenu().addItem(1, R.drawable.msg_settings);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 1) {
                    presentFragment(new HiddenChatsSettingsActivity());
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
            if (position < 0 || position >= dialogs.size()) {
                return;
            }
            final long dialogId = dialogs.get(position);
            final Bundle args = new Bundle();
            if (DialogObject.isUserDialog(dialogId)) {
                args.putLong("user_id", dialogId);
            } else if (DialogObject.isEncryptedDialog(dialogId)) {
                args.putInt("enc_id", DialogObject.getEncryptedChatId(dialogId));
            } else {
                args.putLong("chat_id", -dialogId);
            }
            presentFragment(new ChatActivity(args));
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (position < 0 || position >= dialogs.size()) {
                return false;
            }
            final long dialogId = dialogs.get(position);
            showDialog(new AlertDialog.Builder(context)
                    .setTitle(dialogName(dialogId))
                    .setMessage(LocaleController.getString(R.string.HiddenChatsUnhideAsk))
                    .setPositiveButton(LocaleController.getString(R.string.HiddenChatsUnhide), (d, w) -> {
                        HiddenChats.setHidden(currentAccount, dialogId, false);
                        reload();
                    })
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .create());
            return true;
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(AndroidUtilities.dp(32), 0, AndroidUtilities.dp(32), 0);
        emptyView.setText(LocaleController.getString(R.string.HiddenChatsEmpty));
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        reload();
        return fragmentView;
    }

    private void reload() {
        dialogs = HiddenChats.getHidden(currentAccount);
        // Most recent conversation first.
        java.util.Collections.sort(dialogs, (a, b) -> Integer.compare(lastDate(b), lastDate(a)));
        if (listView != null && listView.getAdapter() != null) {
            listView.getAdapter().notifyDataSetChanged();
        }
        if (emptyView != null) {
            emptyView.setVisibility(dialogs.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private int lastDate(long dialogId) {
        final TLRPC.Dialog d = getMessagesController().dialogs_dict.get(dialogId);
        return d == null ? 0 : d.last_message_date;
    }

    private String dialogName(long dialogId) {
        if (DialogObject.isUserDialog(dialogId)) {
            final TLRPC.User user = getMessagesController().getUser(dialogId);
            if (user != null) {
                return ContactsController.formatName(user.first_name, user.last_name);
            }
        } else if (!DialogObject.isEncryptedDialog(dialogId)) {
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
            cell.setMultilineDetail(false);
            cell.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final long dialogId = dialogs.get(position);
            String title = dialogName(dialogId);
            final TLRPC.Dialog d = getMessagesController().dialogs_dict.get(dialogId);
            if (d != null && d.unread_count > 0) {
                title += "  (" + d.unread_count + ")";
            }
            CharSequence detail = "";
            final ArrayList<MessageObject> last = getMessagesController().dialogMessage.get(dialogId);
            if (last != null && !last.isEmpty() && last.get(0) != null) {
                final MessageObject m = last.get(0);
                final CharSequence text = !TextUtils.isEmpty(m.caption) ? m.caption : m.messageText;
                detail = text == null ? "" : text.toString().replace('\n', ' ');
            }
            ((TextDetailSettingsCell) holder.itemView).setTextAndValue(title, detail, position < dialogs.size() - 1);
        }

        @Override
        public int getItemCount() {
            return dialogs.size();
        }
    }
}
