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
import tw.nekomimi.nekogram.helpers.LocalLastSeen;

/** The contact changes log; tap opens the contact, long press removes an entry. */
public class ContactChangesActivity extends BaseFragment {

    private ArrayList<ContactChanges.Event> events = new ArrayList<>();
    private RecyclerListView listView;
    private TextView emptyView;
    private long timelineUserId;
    private int displayTypes = ContactChanges.TYPES_ALL;
    private boolean activityMode;
    private boolean receiptsMode;
    private ArrayList<LocalLastSeen.Event> activityEvents = new ArrayList<>();

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
            case ContactChanges.TYPE_BIO:
                return R.string.ContactChangeBio;
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
        actionBar.createMenu().addItem(2, R.drawable.ic_ab_back);
        actionBar.createMenu().addItem(3, R.drawable.msg_photos);
        actionBar.createMenu().addItem(5, R.drawable.msg_search);
        actionBar.createMenu().addItem(4, R.drawable.msg_info);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 2) {
                    timelineUserId = 0;
                    reload();
                } else if (id == 3) {
                    showArchive();
                } else if (id == 5) {
                    if (activityMode) return;
                    CharSequence[] labels = new CharSequence[ContactChanges.TYPES.length + 1];
                    labels[0] = LocaleController.getString(R.string.ContactChangesTitle);
                    for (int i = 0; i < ContactChanges.TYPES.length; i++) labels[i + 1] = LocaleController.getString(typeName(ContactChanges.TYPES[i]));
                    showDialog(new AlertDialog.Builder(getParentActivity()).setTitle(LocaleController.getString(R.string.ContactChangesTitle))
                            .setItems(labels, (d, which) -> {
                                displayTypes = which == 0 ? ContactChanges.TYPES_ALL : ContactChanges.TYPES[which - 1];
                                reload();
                            }).create());
                } else if (id == 4) {
                    showDialog(new AlertDialog.Builder(getParentActivity())
                            .setTitle(LocaleController.getString(R.string.ObservedActivityTitle))
                            .setItems(new CharSequence[]{LocaleController.getString(R.string.ContactChangesTitle),
                                    LocaleController.getString(R.string.ObservedActivityTitle),
                                    LocaleController.getString(R.string.OutgoingReadHistory)}, (d, which) -> {
                                activityMode = which != 0;
                                receiptsMode = which == 2;
                                reload();
                            }).create());
                } else if (id == 1 && getParentActivity() != null) {
                    showDialog(new AlertDialog.Builder(getParentActivity())
                            .setTitle(LocaleController.getString(R.string.ContactChangesTitle))
                            .setMessage(LocaleController.getString(R.string.ContactChangesClearAsk))
                            .setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> {
                                if (activityMode) LocalLastSeen.clearHistory(currentAccount, timelineUserId, receiptsMode);
                                else ContactChanges.getInstance(currentAccount).clear();
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
            if (position >= 0 && position < (activityMode ? activityEvents.size() : events.size())) {
                final long userId = activityMode ? activityEvents.get(position).userId : events.get(position).userId;
                if (timelineUserId == 0) {
                    // Reuse the existing change events instead of introducing a parallel timeline store.
                    timelineUserId = userId;
                    reload();
                } else {
                    final Bundle args = new Bundle();
                    args.putLong("user_id", userId);
                    presentFragment(new ProfileActivity(args));
                }
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (activityMode) return false;
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
        activityEvents = LocalLastSeen.getHistory(currentAccount, timelineUserId);
        activityEvents.removeIf(event -> (event.source == LocalLastSeen.READ) != receiptsMode);
        events = timelineUserId == 0
                ? ContactChanges.getInstance(currentAccount).getEvents()
                : ContactChanges.getInstance(currentAccount).getEventsForContact(timelineUserId);
        events.removeIf(event -> (event.type & displayTypes) == 0);
        if (actionBar != null) {
            if (timelineUserId == 0) {
                actionBar.setTitle(LocaleController.getString(activityMode
                        ? (receiptsMode ? R.string.OutgoingReadHistory : R.string.ObservedActivityTitle) : R.string.ContactChangesTitle));
            } else {
                final TLRPC.User user = getMessagesController().getUser(timelineUserId);
                actionBar.setTitle(user == null ? String.valueOf(timelineUserId) : UserObject.getUserName(user));
            }
        }
        if (listView != null && listView.getAdapter() != null) {
            listView.getAdapter().notifyDataSetChanged();
        }
        if (emptyView != null) {
            emptyView.setText(LocaleController.getString(activityMode ? R.string.ObservedActivityNotice : R.string.ContactChangesEmpty));
            emptyView.setVisibility((activityMode ? activityEvents.isEmpty() : events.isEmpty()) ? View.VISIBLE : View.GONE);
        }
    }

    private void showArchive() {
        final ContactChanges changes = ContactChanges.getInstance(currentAccount);
        final java.io.File[] files = changes.getArchivedPhotos(timelineUserId);
        if (files != null) java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.ContactPhotoArchive));
        if (files == null || files.length == 0) {
            builder.setMessage(LocaleController.getString(R.string.ContactPhotoArchiveEmpty));
        } else {
            CharSequence[] labels = new CharSequence[files.length];
            for (int i = 0; i < files.length; i++) {
                String id = files[i].getName().split("_")[0];
                TLRPC.User user = getMessagesController().getUser(Long.parseLong(id));
                labels[i] = (user == null ? id : UserObject.getUserName(user)) + " · "
                        + LocaleController.formatDateAudio(files[i].lastModified() / 1000, false);
            }
            builder.setItems(labels, (dialog, which) -> showPhotoComparison(files[which]));
        }
        if (timelineUserId != 0) {
            boolean excluded = ContactChanges.archiveExcluded(currentAccount).contains(String.valueOf(timelineUserId));
            builder.setNeutralButton(LocaleController.getString(excluded ? R.string.ContactPhotoInclude : R.string.ContactPhotoExclude),
                    (d, w) -> ContactChanges.setArchiveExcluded(currentAccount, timelineUserId, !excluded));
        }
        builder.setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> showDialog(
                new AlertDialog.Builder(getParentActivity()).setTitle(LocaleController.getString(R.string.ContactPhotoArchive))
                        .setMessage(LocaleController.getString(R.string.ContactPhotoDeleteAsk))
                        .setPositiveButton(LocaleController.getString(R.string.Delete), (confirm, button) -> changes.clearArchive(timelineUserId))
                        .setNegativeButton(LocaleController.getString(R.string.Cancel), null).create()));
        builder.setNegativeButton(LocaleController.getString(R.string.Close), null);
        showDialog(builder.create());
    }

    private void showPhotoComparison(java.io.File archived) {
        long userId = Long.parseLong(archived.getName().split("_")[0]);
        TLRPC.User user = getMessagesController().getUser(userId);
        android.widget.LinearLayout layout = new android.widget.LinearLayout(getParentActivity());
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        for (int i = 0; i < 2; i++) {
            TextView label = new TextView(getParentActivity());
            label.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            label.setGravity(Gravity.CENTER);
            label.setText(LocaleController.getString(i == 0 ? R.string.ContactPhotoCurrent : R.string.ContactPhotoArchived));
            layout.addView(label, new android.widget.LinearLayout.LayoutParams(-1, AndroidUtilities.dp(32)));
            org.telegram.ui.Components.BackupImageView image = new org.telegram.ui.Components.BackupImageView(getParentActivity());
            if (i == 0 && user != null && user.photo != null) {
                image.setImage(org.telegram.messenger.ImageLocation.getForUser(currentAccount, user, org.telegram.messenger.ImageLocation.TYPE_BIG),
                        "240_240", (android.graphics.drawable.Drawable) null, user);
            } else if (i == 1) {
                image.setImage(archived.getAbsolutePath(), "240_240", (android.graphics.drawable.Drawable) null);
            }
            layout.addView(image, new android.widget.LinearLayout.LayoutParams(-1, AndroidUtilities.dp(170)));
        }
        showDialog(new AlertDialog.Builder(getParentActivity()).setTitle(LocaleController.getString(R.string.ContactPhotoArchive))
                .setView(layout).setPositiveButton(LocaleController.getString(R.string.Open), (d, w) -> AndroidUtilities.openForView(
                        archived, archived.getName(), "image/jpeg", getParentActivity(), getResourceProvider(), false))
                .setNeutralButton(LocaleController.getString(ContactChanges.archiveExcluded(currentAccount).contains(String.valueOf(userId))
                                ? R.string.ContactPhotoInclude : R.string.ContactPhotoExclude),
                        (d, w) -> ContactChanges.setArchiveExcluded(currentAccount, userId,
                                !ContactChanges.archiveExcluded(currentAccount).contains(String.valueOf(userId))))
                .setNegativeButton(LocaleController.getString(R.string.Close), null).create());
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
            if (activityMode) {
                LocalLastSeen.Event e = activityEvents.get(position);
                TLRPC.User user = getMessagesController().getUser(e.userId);
                String name = user == null ? String.valueOf(e.userId) : UserObject.getUserName(user);
                int label;
                switch (e.source) {
                    case LocalLastSeen.MESSAGE: label = R.string.ActivityMessage; break;
                    case LocalLastSeen.TYPING: label = R.string.ActivityTyping; break;
                    case LocalLastSeen.READ: label = R.string.ActivityRead; break;
                    case LocalLastSeen.ONLINE: label = R.string.ActivityOnline; break;
                    case LocalLastSeen.OFFLINE: label = R.string.ActivityOffline; break;
                    default: label = R.string.ObservedActivityTitle;
                }
                String detail = LocaleController.formatDateAudio(e.date, false);
                detail += "\n" + LocaleController.getString(R.string.ActivityObservedAt) + " "
                        + LocaleController.formatDateAudio(e.observedAt / 1000, false);
                if (e.source == LocalLastSeen.READ) {
                    detail = LocaleController.formatString(R.string.ActivityReadRange, e.fromMessageId, e.maxMessageId)
                            + "\n" + LocaleController.getString(R.string.ActivityObservedAt) + " "
                            + LocaleController.formatDateAudio(e.observedAt / 1000, false);
                }
                ((TextDetailSettingsCell) holder.itemView).setTextAndValue(name + " · " + LocaleController.getString(label),
                        detail, position < activityEvents.size() - 1);
                return;
            }
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
            detail.append('\n').append(LocaleController.getString(R.string.ContactObservedLocally));
            ((TextDetailSettingsCell) holder.itemView).setTextAndValue(title, detail, position < events.size() - 1);
        }

        @Override
        public int getItemCount() {
            return activityMode ? activityEvents.size() : events.size();
        }
    }
}
