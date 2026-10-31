package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.ButtonSpan;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.TableView;
import org.telegram.ui.Components.TextHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;
import org.telegram.utils.proxy.ProxySettings;
import org.telegram.utils.proxy.ProxyVpnGuard;
import org.telegram.utils.proxy.V2RayConfig;
import org.telegram.utils.proxy.V2RayTransport;

import java.util.List;

/**
 * Bottom sheet that shows a V2Ray share link, tests it and connects to it. Opened when a
 * vless://, vmess://, trojan:// or ss:// link is tapped in a message, opened from another app,
 * imported from the clipboard or inspected from the proxy list.
 */
public class V2RayConfigSheet implements NotificationCenter.NotificationCenterDelegate {

    /** Connection attempts that haven't reached Telegram after this long are reported as failed. */
    private static final long CONNECT_TIMEOUT_MS = 25_000;
    public static final int HIGH_PING_MS = 1000;

    private final Activity activity;
    private final V2RayConfig config;
    private final ProxySettings settings;
    private final int currentAccount = UserConfig.selectedAccount;
    private Runnable onEdit;

    private BottomSheet sheet;
    private TextView statusTextView;
    private ButtonWithCounterView connectButton;
    private TextView secondaryButton;

    private boolean checking;
    private boolean connecting;
    private long connectStartTime;
    private final Runnable connectTimeout = this::onConnectTimeout;

    private V2RayConfigSheet(Activity activity, V2RayConfig config) {
        this.activity = activity;
        this.config = config;
        this.settings = ProxySettings.fromV2RayLink(config.link);
    }

    /**
     * Shows the import sheet for the V2Ray links in {@code text}.
     *
     * @return false if {@code text} has no valid V2Ray link.
     */
    public static boolean open(Activity activity, CharSequence text) {
        if (activity == null) {
            return false;
        }
        final List<String> links = V2RayConfig.findLinks(text);
        if (links.isEmpty()) {
            return false;
        }
        if (links.size() == 1) {
            show(activity, links.get(0));
        } else {
            showMany(activity, links);
        }
        return true;
    }

    public static void show(Activity activity, String link) {
        show(activity, link, null);
    }

    /** @param onEdit if set, the sheet gets an "Edit" button that dismisses it and runs this. */
    public static void show(Activity activity, String link, Runnable onEdit) {
        if (activity == null) {
            return;
        }
        final V2RayConfig config;
        try {
            config = V2RayConfig.parseOrThrow(link);
        } catch (Exception e) {
            showInvalid(activity, e);
            return;
        }
        final V2RayConfigSheet sheet = new V2RayConfigSheet(activity, config);
        sheet.onEdit = onEdit;
        sheet.create();
    }

    public static void showInvalid(Activity activity, Exception e) {
        if (activity == null) {
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(getString(R.string.V2RayInvalidTitle))
                .setMessage(LocaleController.formatString(R.string.V2RayInvalidText, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()))
                .setPositiveButton(getString(R.string.OK), null)
                .show();
    }

    private void create() {
        final BottomSheet.Builder builder = new BottomSheet.Builder(activity);
        builder.setApplyTopPadding(false);
        builder.setApplyBottomPadding(false);

        final LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        builder.setCustomView(layout);

        final TextView titleView = TextHelper.makeTextView(activity, 20, Theme.key_dialogTextBlack, true);
        titleView.setText(getString(R.string.V2RayServerTitle));
        titleView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        layout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.FILL_HORIZONTAL, 22, 18, 22, 0));

        final TextView subtitleView = TextHelper.makeTextView(activity, 14, Theme.key_dialogTextGray2, false);
        subtitleView.setText(config.getDisplayName());
        subtitleView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        layout.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.FILL_HORIZONTAL, 22, 4, 22, 0));

        final TableView tableView = new TableView(activity, null);
        layout.addView(tableView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.FILL_HORIZONTAL, 14, 16, 14, 0));
        if (!TextUtils.isEmpty(config.remark)) {
            tableView.addRow(getString(R.string.V2RayName), config.remark);
        }
        tableView.addRow(getString(R.string.V2RayProtocol), config.getProtocolName());
        tableView.addRow(getString(R.string.UseProxyAddress), config.address);
        tableView.addRow(getString(R.string.UseProxyPort), Integer.toString(config.port));
        if (!"ss".equals(config.protocol)) {
            tableView.addRow(getString(R.string.V2RayTransport), config.getNetworkName());
            tableView.addRow(getString(R.string.V2RaySecurity), "none".equals(config.security) ? getString(R.string.V2RaySecurityNone) : config.security.toUpperCase());
        }
        if (!TextUtils.isEmpty(config.sni)) {
            tableView.addRow("SNI", config.sni);
        }
        if (!TextUtils.isEmpty(config.host) && !config.host.equals(config.sni)) {
            tableView.addRow(getString(R.string.V2RayHost), config.host);
        }
        if (!TextUtils.isEmpty(config.path) && !"/".equals(config.path)) {
            tableView.addRow(getString(R.string.V2RayPath), config.path);
        }
        final ButtonSpan.TextViewButtons[] statusRef = new ButtonSpan.TextViewButtons[1];
        tableView.addRow(getString(R.string.ProxyStatus), "", statusRef);
        statusTextView = statusRef[0];
        ((View) statusTextView.getParent()).setPadding(0, 0, 0, 0);
        statusRef[0].setDisablePaddingsOffsetY(true);
        statusTextView.setPadding(dp(12.66f), dp(9.33f), dp(12.66f), dp(9.33f));
        setCheckLink();

        connectButton = new ButtonWithCounterView(activity, null).setRound();
        connectButton.setOnClickListener(v -> {
            if (connecting) {
                return;
            }
            if (isActive() && isConnected()) {
                dismiss();
            } else {
                connect();
            }
        });
        layout.addView(connectButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, Gravity.TOP | Gravity.FILL_HORIZONTAL, 14, 18, 14, 0));

        final LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        layout.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, Gravity.TOP | Gravity.FILL_HORIZONTAL, 14, 6, 14, 10));

        final TextView copyButton = makeTextButton(getString(R.string.V2RayCopyLink));
        copyButton.setOnClickListener(v -> {
            AndroidUtilities.addToClipboard(config.link);
            copyButton.setText(getString(R.string.LinkCopied));
        });
        buttons.addView(copyButton, LayoutHelper.createLinear(0, 48, 1f));

        if (onEdit != null) {
            final TextView editButton = makeTextButton(getString(R.string.Edit));
            editButton.setOnClickListener(v -> {
                dismiss();
                onEdit.run();
            });
            buttons.addView(editButton, LayoutHelper.createLinear(0, 48, 1f, 6, 0, 6, 0));
        }

        secondaryButton = makeTextButton("");
        secondaryButton.setOnClickListener(v -> {
            if (isActive()) {
                disconnect();
            } else {
                final boolean existed = findSaved() != null;
                SharedConfig.addProxy(new SharedConfig.ProxyInfo(settings));
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                dismiss();
                showBulletin(getString(existed ? R.string.V2RayAlreadySaved : R.string.V2RaySaved));
            }
        });
        buttons.addView(secondaryButton, LayoutHelper.createLinear(0, 48, 1f));

        updateButtons();

        sheet = builder.create();
        sheet.setOnDismissListener(d -> {
            AndroidUtilities.cancelRunOnUIThread(connectTimeout);
            NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.didUpdateConnectionState);
        });
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.didUpdateConnectionState);
        sheet.show();

        if (MessagesController.getGlobalMainSettings().getBoolean("proxycheckstatusip", false)) {
            checkDelay();
        }
    }

    private TextView makeTextButton(CharSequence text) {
        final TextView textView = new TextView(activity);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        textView.setTypeface(AndroidUtilities.bold());
        textView.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        textView.setGravity(Gravity.CENTER);
        textView.setBackground(Theme.createRadSelectorDrawable(Theme.multAlpha(Theme.getColor(Theme.key_featuredStickers_addButton), .10f), 8, 8));
        textView.setText(text);
        return textView;
    }

    private SharedConfig.ProxyInfo findSaved() {
        SharedConfig.loadProxyList();
        for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
            if (settings.equals(info.settings)) {
                return info;
            }
        }
        return null;
    }

    private boolean isActive() {
        return SharedConfig.currentProxy != null && settings.equals(SharedConfig.currentProxy.settings)
                && MessagesController.getGlobalMainSettings().getBoolean("proxy_enabled", false);
    }

    private boolean isConnected() {
        final int state = ConnectionsManager.getInstance(currentAccount).getConnectionState();
        return state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating;
    }

    private void updateButtons() {
        if (connecting) {
            connectButton.setText(getString(R.string.V2RayConnecting), true);
            connectButton.setLoading(true);
        } else {
            connectButton.setLoading(false);
            if (isActive() && isConnected()) {
                connectButton.setText(getString(R.string.V2RayConnectedDone), true);
            } else {
                connectButton.setText(getString(R.string.V2RayConnect), true);
            }
        }
        secondaryButton.setText(getString(isActive() ? R.string.V2RayDisconnect : R.string.V2RaySaveOnly));
    }

    private void setCheckLink() {
        statusTextView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        statusTextView.setText(AndroidUtilities.replaceSingleLink(getString(R.string.ProxyBottomSheetCheckStatus), Theme.getColor(Theme.key_chat_messageLinkIn), () -> {
            final SharedPreferences pref = MessagesController.getGlobalMainSettings();
            if (pref.getBoolean("proxycheckstatusip", false)) {
                checkDelay();
            } else {
                new AlertDialog.Builder(activity)
                        .setTitle(getString(R.string.ProxyBottomSheetCheckWarning))
                        .setMessage(getString(R.string.ProxyBottomSheetCheckWarningText))
                        .setPositiveButton(getString(R.string.Proceed), (di, w) -> {
                            pref.edit().putBoolean("proxycheckstatusip", true).apply();
                            checkDelay();
                        })
                        .setNegativeButton(getString(R.string.Cancel), null)
                        .show();
            }
        }));
    }

    private void checkDelay() {
        if (checking) {
            return;
        }
        checking = true;
        statusTextView.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        statusTextView.setText(getString(R.string.ProxyBottomSheetChecking) + "…");
        V2RayTransport.checkDelay(config.link, time -> AndroidUtilities.runOnUIThread(() -> {
            checking = false;
            final SharedConfig.ProxyInfo saved = findSaved();
            if (saved != null) {
                saved.checking = false;
                saved.availableCheckTime = android.os.SystemClock.elapsedRealtime();
                saved.available = time != -1;
                saved.ping = time == -1 ? 0 : time;
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyCheckDone, saved);
            }
            if (connecting) {
                return;
            }
            showDelay(time);
        }));
    }

    private void showDelay(long time) {
        if (time == -1) {
            statusTextView.setText(getString(R.string.V2RayNoResponse));
            statusTextView.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        } else {
            statusTextView.setText(LocaleController.formatString(R.string.V2RayPingMs, time));
            statusTextView.setTextColor(Theme.getColor(time >= HIGH_PING_MS ? Theme.key_color_orange : Theme.key_windowBackgroundWhiteGreenText));
        }
        statusTextView.setOnClickListener(v -> checkDelay());
    }

    private void connect() {
        final SharedConfig.ProxyInfo info = SharedConfig.addProxy(new SharedConfig.ProxyInfo(settings));
        applyProxy(info, true);

        connecting = true;
        connectStartTime = System.currentTimeMillis();
        statusTextView.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        statusTextView.setText(getString(R.string.V2RayConnectingStatus));
        updateButtons();
        AndroidUtilities.cancelRunOnUIThread(connectTimeout);
        AndroidUtilities.runOnUIThread(connectTimeout, CONNECT_TIMEOUT_MS);
        AndroidUtilities.runOnUIThread(this::checkConnected, 1500);
    }

    private void disconnect() {
        final SharedConfig.ProxyInfo info = findSaved();
        if (info != null) {
            applyProxy(info, false);
        }
        connecting = false;
        AndroidUtilities.cancelRunOnUIThread(connectTimeout);
        updateButtons();
        setCheckLink();
    }

    /** Makes {@code info} the current proxy and turns the proxy on or off, like the proxy list does. */
    public static void applyProxy(SharedConfig.ProxyInfo info, boolean enable) {
        SharedConfig.currentProxy = info;
        final SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        info.settings.toSharedPreferences(editor);
        editor.putBoolean("proxy_enabled", enable);
        editor.commit();
        ProxyVpnGuard.onUserChangedProxy();
        ConnectionsManager.setProxySettings(enable, info.settings);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }

    private void checkConnected() {
        if (!connecting) {
            return;
        }
        if (!V2RayTransport.isRunning()) {
            onConnectFailed(getString(R.string.V2RayCoreFailed));
            return;
        }
        if (isConnected() && System.currentTimeMillis() - connectStartTime >= 1500) {
            onConnected();
        }
    }

    private void onConnected() {
        connecting = false;
        AndroidUtilities.cancelRunOnUIThread(connectTimeout);
        statusTextView.setText(getString(R.string.V2RayConnectedStatus));
        statusTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGreenText));
        updateButtons();
        AndroidUtilities.runOnUIThread(() -> {
            dismiss();
            showBulletin(LocaleController.formatString(R.string.V2RayConnectedBulletin, config.getDisplayName()));
        }, 900);
    }

    private void onConnectTimeout() {
        if (connecting) {
            onConnectFailed(getString(R.string.V2RayConnectFailed));
        }
    }

    private void onConnectFailed(String reason) {
        connecting = false;
        AndroidUtilities.cancelRunOnUIThread(connectTimeout);
        statusTextView.setText(reason);
        statusTextView.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        statusTextView.setOnClickListener(v -> checkDelay());
        connectButton.setLoading(false);
        connectButton.setText(getString(R.string.V2RayRetry), true);
        secondaryButton.setText(getString(isActive() ? R.string.V2RayDisconnect : R.string.V2RaySaveOnly));
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didUpdateConnectionState) {
            if (connecting) {
                AndroidUtilities.runOnUIThread(this::checkConnected, Math.max(0, 1500 - (System.currentTimeMillis() - connectStartTime)));
            } else if (sheet != null && sheet.isShowing()) {
                updateButtons();
            }
        }
    }

    private void dismiss() {
        if (sheet != null) {
            sheet.dismiss();
        }
    }

    private static void showBulletin(String text) {
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.showBulletin, Bulletin.TYPE_SUCCESS, text);
    }

    private static void showMany(Activity activity, List<String> links) {
        final StringBuilder names = new StringBuilder();
        final int shown = Math.min(links.size(), 8);
        for (int i = 0; i < shown; i++) {
            final V2RayConfig config = V2RayConfig.parse(links.get(i));
            if (config != null) {
                names.append("• ").append(config.getDisplayName()).append('\n');
            }
        }
        if (links.size() > shown) {
            names.append(LocaleController.formatString(R.string.V2RayAndMore, links.size() - shown));
        }
        new AlertDialog.Builder(activity)
                .setTitle(LocaleController.formatString(R.string.V2RayFoundMany, links.size()))
                .setMessage(names.toString().trim())
                .setPositiveButton(getString(R.string.V2RayAddAll), (d, w) -> {
                    int added = 0;
                    for (String link : links) {
                        final ProxySettings settings = ProxySettings.fromV2RayLink(link);
                        if (settings != null) {
                            final int before = SharedConfig.proxyList.size();
                            SharedConfig.addProxy(new SharedConfig.ProxyInfo(settings));
                            if (SharedConfig.proxyList.size() > before) {
                                added++;
                            }
                        }
                    }
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                    showBulletin(LocaleController.formatString(R.string.V2RayAddedCount, added));
                    final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
                    if (fragment != null && !(fragment instanceof ProxyListActivity)) {
                        fragment.presentFragment(new ProxyListActivity());
                    }
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }
}
