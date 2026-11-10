package tw.nekomimi.nekogram.settings;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.utils.proxy.ConnectionKeepAlive;
import org.telegram.utils.proxy.ProxyVpnGuard;

/** Parvagram › Connection & proxy: V2Ray, subscriptions, the VPN guard and staying connected. */
public class ParvaConnectionSettingsActivity extends ParvaPageActivity {

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.ParvaConnectionTitle);
    }

    @Override
    protected void buildRows() {
        header(R.string.ParvaProxyHeader);
        link("proxy", R.string.ProxySettings, R.string.ParvaProxyListInfo, () -> presentFragment(new org.telegram.ui.ProxyListActivity()));
        link("subscriptions", R.string.V2RaySubscriptions, R.string.ParvaSubscriptionsInfo, () -> presentFragment(new org.telegram.ui.V2RaySubscriptionsActivity()));
        check("DisableProxyWhenVpnEnabled", R.string.ProxyVpnGuard, R.string.ProxyVpnGuardInfo,
                ProxyVpnGuard::isEnabled, ProxyVpnGuard::setEnabled);
        // Telegram's proxy rotation (the same switch as in the proxy list), for MTProto and V2Ray alike.
        check("proxyRotation", R.string.UseProxyRotation, R.string.ParvaProxyRotationInfo,
                () -> org.telegram.messenger.SharedConfig.proxyRotationEnabled, v -> {
                    org.telegram.messenger.SharedConfig.proxyRotationEnabled = v;
                    org.telegram.messenger.SharedConfig.saveConfig();
                    org.telegram.messenger.NotificationCenter.getGlobalInstance().postNotificationName(org.telegram.messenger.NotificationCenter.proxySettingsChanged);
                    refresh();
                });
        if (org.telegram.messenger.SharedConfig.proxyRotationEnabled) {
            choice("proxyRotationTimeout", R.string.ParvaProxyRotationWait,
                    () -> LocaleController.formatString(R.string.ProxyRotationTimeoutSeconds,
                            org.telegram.messenger.ProxyRotationController.ROTATION_TIMEOUTS.get(org.telegram.messenger.SharedConfig.proxyRotationTimeout)),
                    this::pickRotationTimeout);
        }
        shadow();

        header(R.string.KeepAliveTitle);
        check("keepAlive", R.string.ProxyKeepAlive, R.string.ProxyKeepAliveInfo, ConnectionKeepAlive::isEnabled, v -> {
            ConnectionKeepAlive.setEnabled(v);
            if (v && getParentActivity() != null) {
                ConnectionKeepAlive.requestBatteryExemption(getParentActivity());
            }
        });
        info(R.string.ProxyConnectionInfo);
    }

    private void pickRotationTimeout() {
        if (getParentActivity() == null) {
            return;
        }
        final java.util.List<Integer> timeouts = org.telegram.messenger.ProxyRotationController.ROTATION_TIMEOUTS;
        final CharSequence[] items = new CharSequence[timeouts.size()];
        for (int i = 0; i < items.length; i++) {
            items[i] = (i == org.telegram.messenger.SharedConfig.proxyRotationTimeout ? "✓ " : "")
                    + LocaleController.formatString(R.string.ProxyRotationTimeoutSeconds, timeouts.get(i));
        }
        showDialog(new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.ParvaProxyRotationWait))
                .setItems(items, (d, which) -> {
                    org.telegram.messenger.SharedConfig.proxyRotationTimeout = which;
                    org.telegram.messenger.SharedConfig.saveConfig();
                    refresh();
                })
                .create());
    }
}
