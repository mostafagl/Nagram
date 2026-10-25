package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.utils.proxy.ProxySettings;
import org.telegram.utils.proxy.V2RayConfig;
import org.telegram.utils.proxy.V2RaySubscriptions;

import java.util.ArrayList;
import java.util.List;

/** Lists V2Ray subscriptions, adds/edits/removes them and runs updates with live progress. */
public class V2RaySubscriptionsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private LinearLayout listLayout;
    private volatile boolean cancelUpdate;

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxySettingsChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.proxySettingsChanged && listLayout != null) {
            rebuild();
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.V2RaySubscriptions));
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
        root.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listLayout = new LinearLayout(context);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(listLayout, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        rebuild();
        return fragmentView;
    }

    private void rebuild() {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        listLayout.removeAllViews();
        final List<V2RaySubscriptions.Subscription> subscriptions = V2RaySubscriptions.getAll();

        final LinearLayout section = new LinearLayout(context);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        final HeaderCell header = new HeaderCell(context);
        header.setText(getString(R.string.V2RaySubscriptions));
        section.addView(header);

        for (V2RaySubscriptions.Subscription s : subscriptions) {
            final TextDetailSettingsCell cell = new TextDetailSettingsCell(context);
            cell.setBackground(Theme.getSelectorDrawable(false));
            cell.setMultilineDetail(true);
            cell.setTextAndValue(s.getDisplayName(), describe(s), true);
            cell.setOnClickListener(v -> showOptions(s));
            section.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        final TextSettingsCell add = new TextSettingsCell(context);
        add.setBackground(Theme.getSelectorDrawable(false));
        add.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        add.setText(getString(R.string.V2RaySubscriptionAdd), !subscriptions.isEmpty());
        add.setOnClickListener(v -> showEditor(null));
        section.addView(add);

        if (!subscriptions.isEmpty()) {
            final TextSettingsCell updateAll = new TextSettingsCell(context);
            updateAll.setBackground(Theme.getSelectorDrawable(false));
            updateAll.setText(getString(R.string.V2RaySubscriptionUpdateAll), false);
            updateAll.setOnClickListener(v -> runUpdates(new ArrayList<>(subscriptions)));
            section.addView(updateAll);
        }
        listLayout.addView(section, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextInfoPrivacyCell info = new TextInfoPrivacyCell(context);
        info.setText(getString(R.string.V2RaySubscriptionInfo));
        listLayout.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private String describe(V2RaySubscriptions.Subscription s) {
        final StringBuilder sb = new StringBuilder();
        sb.append(LocaleController.formatString(R.string.V2RaySubscriptionCount, countSaved(s), s.maxConfigs));
        sb.append(" · ").append(intervalName(s.intervalMinutes));
        if (s.lastUpdate > 0) {
            sb.append("\n").append(LocaleController.formatString(R.string.V2RaySubscriptionLastUpdate, LocaleController.formatDateTime(s.lastUpdate / 1000, true)));
        }
        if (!TextUtils.isEmpty(s.lastError)) {
            sb.append("\n").append(LocaleController.formatString(R.string.V2RaySubscriptionLastError, s.lastError));
        }
        return sb.toString();
    }

    private static int countSaved(V2RaySubscriptions.Subscription s) {
        int count = 0;
        for (SharedConfig.ProxyInfo info : SharedConfig.getProxyList()) {
            if (info.settings.getType() == ProxySettings.Type.V2RAY) {
                final V2RayConfig config = V2RayConfig.parse(info.settings.getSecret());
                if (config != null && s.keys.contains(config.getDedupKey())) {
                    count++;
                }
            }
        }
        return count;
    }

    private static String intervalName(int minutes) {
        if (minutes <= 0) {
            return getString(R.string.V2RaySubscriptionManual);
        } else if (minutes < 60) {
            return LocaleController.formatString(R.string.V2RaySubscriptionEveryMinutes, minutes);
        } else {
            return LocaleController.formatString(R.string.V2RaySubscriptionEveryHours, minutes / 60);
        }
    }

    private void showOptions(V2RaySubscriptions.Subscription s) {
        if (getParentActivity() == null) {
            return;
        }
        final CharSequence[] items = {getString(R.string.V2RaySubscriptionUpdateNow), getString(R.string.Edit), getString(R.string.V2RayCopyLink), getString(R.string.Delete)};
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(s.getDisplayName());
        builder.setItems(items, (d, which) -> {
            if (which == 0) {
                final ArrayList<V2RaySubscriptions.Subscription> list = new ArrayList<>();
                list.add(s);
                runUpdates(list);
            } else if (which == 1) {
                showEditor(s);
            } else if (which == 2) {
                AndroidUtilities.addToClipboard(s.url);
            } else {
                confirmDelete(s);
            }
        });
        showDialog(builder.create());
    }

    private void confirmDelete(V2RaySubscriptions.Subscription s) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.V2RaySubscriptionDelete));
        builder.setMessage(LocaleController.formatString(R.string.V2RaySubscriptionDeleteText, s.getDisplayName()));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setPositiveButton(getString(R.string.Delete), (d, w) -> {
            // Its configs stay in the proxy list; they can be removed from there.
            V2RaySubscriptions.remove(s.id);
            rebuild();
        });
        final AlertDialog dialog = builder.create();
        showDialog(dialog);
        final View button = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    private EditTextBoldCursor makeField(Context context, String hint, String value, int inputType) {
        final EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setBackground(null);
        field.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated), Theme.getColor(Theme.key_text_RedBold));
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        field.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        field.setHint(hint);
        field.setInputType(inputType);
        field.setSingleLine(true);
        field.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        if (value != null) {
            field.setText(value);
        }
        return field;
    }

    private void showEditor(V2RaySubscriptions.Subscription existing) {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final V2RaySubscriptions.Subscription s = existing != null ? existing : new V2RaySubscriptions.Subscription();
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), 0);

        final EditTextBoldCursor urlField = makeField(context, getString(R.string.V2RaySubscriptionUrl), existing != null ? s.url : null, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlField.setGravity(Gravity.LEFT);
        layout.addView(urlField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        final EditTextBoldCursor nameField = makeField(context, getString(R.string.V2RaySubscriptionName), existing != null ? s.name : null, InputType.TYPE_CLASS_TEXT);
        layout.addView(nameField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        final EditTextBoldCursor maxField = makeField(context, getString(R.string.V2RaySubscriptionMax), Integer.toString(s.maxConfigs), InputType.TYPE_CLASS_NUMBER);
        layout.addView(maxField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

        final int[] interval = {existing != null ? s.intervalMinutes : 0};
        final TextSettingsCell intervalCell = new TextSettingsCell(context, 24);
        intervalCell.setBackground(Theme.getSelectorDrawable(false));
        intervalCell.setTextAndValue(getString(R.string.V2RaySubscriptionAutoUpdate), intervalName(interval[0]), false);
        intervalCell.setOnClickListener(v -> {
            final CharSequence[] names = new CharSequence[V2RaySubscriptions.INTERVALS_MINUTES.length];
            for (int i = 0; i < names.length; i++) {
                names[i] = intervalName(V2RaySubscriptions.INTERVALS_MINUTES[i]);
            }
            final AlertDialog.Builder picker = new AlertDialog.Builder(context);
            picker.setTitle(getString(R.string.V2RaySubscriptionAutoUpdate));
            picker.setItems(names, (d, which) -> {
                interval[0] = V2RaySubscriptions.INTERVALS_MINUTES[which];
                intervalCell.setTextAndValue(getString(R.string.V2RaySubscriptionAutoUpdate), intervalName(interval[0]), false);
            });
            showDialog(picker.create());
        });
        layout.addView(intervalCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, -24, 0, -24, 0));

        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(existing == null ? R.string.V2RaySubscriptionAdd : R.string.V2RaySubscriptionEdit));
        builder.setView(layout);
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setPositiveButton(getString(existing == null ? R.string.V2RaySubscriptionAddAndUpdate : R.string.Save), (d, w) -> {
            final String url = urlField.getText() == null ? "" : urlField.getText().toString().trim();
            if (!V2RaySubscriptions.isValidUrl(url)) {
                tw.nekomimi.nekogram.utils.AlertUtil.showToast(getString(R.string.V2RaySubscriptionBadUrl));
                return;
            }
            int max = 50;
            try {
                max = Integer.parseInt(maxField.getText().toString().trim());
            } catch (Exception ignore) {
            }
            s.url = url;
            s.name = nameField.getText() == null ? "" : nameField.getText().toString().trim();
            s.maxConfigs = Math.max(1, Math.min(1000, max));
            s.intervalMinutes = interval[0];
            V2RaySubscriptions.save(s);
            rebuild();
            if (existing == null) {
                final ArrayList<V2RaySubscriptions.Subscription> list = new ArrayList<>();
                list.add(s);
                runUpdates(list);
            }
        });
        showDialog(builder.create());
    }

    /** Updates subscriptions one after another in the background, with a cancellable progress dialog. */
    private void runUpdates(List<V2RaySubscriptions.Subscription> subscriptions) {
        if (getParentActivity() == null || subscriptions.isEmpty()) {
            return;
        }
        if (V2RaySubscriptions.isRunning()) {
            tw.nekomimi.nekogram.utils.AlertUtil.showToast(getString(R.string.V2RaySubscriptionBusy));
            return;
        }
        cancelUpdate = false;
        final AlertDialog progressDialog = new AlertDialog.Builder(getParentActivity())
                .setTitle(getString(R.string.V2RaySubscriptionUpdating))
                .setMessage(getString(R.string.V2RaySubscriptionDownloading))
                .setNegativeButton(getString(R.string.Cancel), (d, w) -> cancelUpdate = true)
                .create();
        progressDialog.setCanCancel(false);
        showDialog(progressDialog);

        new Thread(() -> {
            final StringBuilder summary = new StringBuilder();
            for (V2RaySubscriptions.Subscription s : subscriptions) {
                if (cancelUpdate) {
                    break;
                }
                AndroidUtilities.runOnUIThread(() -> progressDialog.setMessage(s.getDisplayName() + "\n" + getString(R.string.V2RaySubscriptionDownloading)));
                final V2RaySubscriptions.Result result = V2RaySubscriptions.update(s.id, new V2RaySubscriptions.Progress() {
                    @Override
                    public void onProgress(int tested, int total, int working) {
                        AndroidUtilities.runOnUIThread(() -> progressDialog.setMessage(s.getDisplayName() + "\n"
                                + LocaleController.formatString(R.string.V2RaySubscriptionTesting, tested, total, working)));
                    }

                    @Override
                    public boolean isCancelled() {
                        return cancelUpdate;
                    }
                }, 30 * 60_000L);
                if (summary.length() > 0) {
                    summary.append("\n\n");
                }
                summary.append(s.getDisplayName()).append("\n").append(describeResult(result));
            }
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    progressDialog.dismiss();
                } catch (Exception ignore) {
                }
                rebuild();
                if (getParentActivity() != null) {
                    final AlertDialog.Builder done = new AlertDialog.Builder(getParentActivity());
                    done.setTitle(getString(R.string.V2RaySubscriptionResultTitle));
                    done.setMessage(summary.toString());
                    done.setPositiveButton(getString(R.string.OK), null);
                    showDialog(done.create());
                }
            });
        }, "v2ray-subscription-ui").start();
    }

    private static String describeResult(V2RaySubscriptions.Result r) {
        if (r.error != null) {
            return LocaleController.formatString(R.string.V2RaySubscriptionFailed, r.error);
        }
        String text = LocaleController.formatString(R.string.V2RaySubscriptionResult, r.links, r.duplicates, r.invalid, r.tested, r.working, r.added);
        if (r.limitReached) {
            text += "\n" + getString(R.string.V2RaySubscriptionLimitReached);
        }
        if (r.cancelled) {
            text += "\n" + getString(R.string.V2RaySubscriptionCancelled);
        }
        return text;
    }
}
