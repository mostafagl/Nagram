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
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.ProfileActivity;

import java.util.ArrayList;

import tw.nekomimi.nekogram.helpers.ContactChanges;

/** The contact changes log; tap opens the contact, long press removes an entry. */
public class ContactChangesActivity extends BaseFragment {

    private ArrayList<ContactChanges.Event> events = new ArrayList<>();
    private RecyclerListView listView;
    private TextView emptyView;

    public static int typeName(int type) {
        switch (type) {
            case ContactChanges.TYPE_PHOTO:
                return R.string.ContactChangePhoto;
            case ContactChanges.TYPE_PHOTO_REMOVED:
                return R.string.ContactChangePhotoRemoved;
            case ContactChanges.TYPE_PHONE:
                return R.string.ContactChangePhone;
            case ContactChanges.TYPE_NAME:
                return R.string.ContactChangeName;
            case ContactChanges.TYPE_USERNAME:
                return R.string.ContactChangeUsername;
            case ContactChanges.TYPE_BLOCKED:
                return R.string.ContactChangeBlocked;
            default:
                return R.string.ContactChangeUnblocked;
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.ContactChangesTitle));
        actionBar.createMenu().addItem(1, R.drawable.msg_delete);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 1 && getParentActivity() != null) {
                    showDialog(new AlertDialog.Builder(getParentActivity())
                            .setTitle(LocaleController.getString(R.string.ContactChangesTitle))
                            .setMessage(LocaleController.getString(R.string.ContactChangesClearAsk))
                            .setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> {
                                ContactChanges.getInstance(currentAccount).clear();
                                reload();
                            })
                            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                            .create());
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
            if (position >= 0 && position < events.size()) {
                final Bundle args = new Bundle();
                args.putLong("user_id", events.get(position).userId);
                presentFragment(new ProfileActivity(args));
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (position < 0 || position >= events.size()) {
                return false;
            }
            ContactChanges.getInstance(currentAccount).remove(events.get(position));
            reload();
            return true;
        });
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(AndroidUtilities.dp(32), 0, AndroidUtilities.dp(32), 0);
        emptyView.setText(LocaleController.getString(R.string.ContactChangesEmpty));
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        ContactChanges.start(currentAccount);
        reload();
        return fragmentView;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        ContactChanges.getInstance(currentAccount).markAllSeen();
    }

    private void reload() {
        events = ContactChanges.getInstance(currentAccount).getEvents();
        if (listView != null && listView.getAdapter() != null) {
            listView.getAdapter().notifyDataSetChanged();
        }
        if (emptyView != null) {
            emptyView.setVisibility(events.isEmpty() ? View.VISIBLE : View.GONE);
        }
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
            final ContactChanges.Event e = events.get(position);
            final TLRPC.User user = getMessagesController().getUser(e.userId);
            final String name = user != null ? UserObject.getUserName(user) : String.valueOf(e.userId);
            final StringBuilder title = new StringBuilder();
            if (!e.seen) {
                title.append("● ");
            }
            title.append(name).append(" · ").append(LocaleController.getString(typeName(e.type)));
            final StringBuilder detail = new StringBuilder();
            if (!TextUtils.isEmpty(e.oldValue) || !TextUtils.isEmpty(e.newValue)) {
                final String prefix = e.type == ContactChanges.TYPE_USERNAME ? "@" : e.type == ContactChanges.TYPE_PHONE ? "+" : "";
                detail.append(TextUtils.isEmpty(e.oldValue) ? "—" : prefix + e.oldValue)
                        .append("  →  ")
                        .append(TextUtils.isEmpty(e.newValue) ? "—" : prefix + e.newValue)
                        .append('\n');
            }
            detail.append(LocaleController.formatDateAudio(e.time / 1000, false));
            ((TextDetailSettingsCell) holder.itemView).setTextAndValue(title, detail, position < events.size() - 1);
        }

        @Override
        public int getItemCount() {
            return events.size();
        }
    }
}
