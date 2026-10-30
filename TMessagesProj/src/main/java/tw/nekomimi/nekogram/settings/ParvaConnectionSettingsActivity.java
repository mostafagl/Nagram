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
}
