package org.telegram.utils.proxy;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.Components.Bulletin;

import xyz.nextalone.nagram.NaConfig;

/**
 * Turns the in-app proxy off while a system VPN carries this app's traffic, and back on when
 * the VPN goes away. Only reacts to the VPN coming up or going down (with a short debounce), so
 * turning the proxy back on by hand while the VPN is up sticks and never ping-pongs.
 */
public final class ProxyVpnGuard {

    private static final String PREF_SUSPENDED = "parva_proxy_suspended_by_vpn";
    private static final long DEBOUNCE_MS = 2000;

    private static boolean initialized;
    private static volatile boolean vpnActive;
    private static Boolean appliedVpnState;

    private static final Runnable evaluate = ProxyVpnGuard::evaluate;

    private ProxyVpnGuard() {
    }

    public static void init() {
        if (initialized || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return;
        }
        initialized = true;
        try {
            final ConnectivityManager cm = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            // The default network is per app, so a VPN that excludes this app isn't reported.
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    update(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
                }

                @Override
                public void onLost(Network network) {
                    update(false);
                }
            });
        } catch (Throwable e) {
            FileLog.e(e);
        }
        // Without any VPN the callback may only report later; evaluate the current state anyway
        // so a proxy suspended before the app was killed comes back.
        AndroidUtilities.runOnUIThread(evaluate, DEBOUNCE_MS * 2);
    }

    /** The existing "Disable proxy when VPN enabled" option (proxy screen and general settings). */
    public static boolean isEnabled() {
        return NaConfig.INSTANCE.getDisableProxyWhenVpnEnabled().Bool();
    }

    public static void setEnabled(boolean enabled) {
        NaConfig.INSTANCE.getDisableProxyWhenVpnEnabled().setConfigBool(enabled);
        if (!enabled) {
            MessagesController.getGlobalMainSettings().edit().remove(PREF_SUSPENDED).apply();
        }
        if (enabled) {
            // Apply to a VPN that is already up.
            appliedVpnState = null;
            AndroidUtilities.runOnUIThread(evaluate);
        }
    }

    public static boolean isVpnActive() {
        return vpnActive;
    }

    /** Whether the proxy is currently off because of a VPN. */
    public static boolean isSuspended() {
        return MessagesController.getGlobalMainSettings().getBoolean(PREF_SUSPENDED, false);
    }

    /** The user picked or toggled a proxy themselves: their choice wins until the VPN changes. */
    public static void onUserChangedProxy() {
        MessagesController.getGlobalMainSettings().edit().remove(PREF_SUSPENDED).apply();
    }

    private static void update(boolean active) {
        if (vpnActive == active && appliedVpnState != null) {
            return;
        }
        vpnActive = active;
        AndroidUtilities.cancelRunOnUIThread(evaluate);
        AndroidUtilities.runOnUIThread(evaluate, DEBOUNCE_MS);
    }

    private static void evaluate() {
        final boolean active = vpnActive;
        if (appliedVpnState != null && appliedVpnState == active) {
            return;
        }
        appliedVpnState = active;
        if (!isEnabled()) {
            return;
        }
        final SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedConfig.loadProxyList();
        final SharedConfig.ProxyInfo proxy = SharedConfig.currentProxy;
        if (active) {
            if (proxy != null && preferences.getBoolean("proxy_enabled", false)) {
                preferences.edit().putBoolean("proxy_enabled", false).putBoolean(PREF_SUSPENDED, true).commit();
                ConnectionsManager.setProxySettings(false, proxy.settings);
                notifyChanged(R.string.ProxyPausedForVpn);
            }
        } else if (preferences.getBoolean(PREF_SUSPENDED, false)) {
            preferences.edit().remove(PREF_SUSPENDED).apply();
            if (proxy != null && !preferences.getBoolean("proxy_enabled", false)) {
                preferences.edit().putBoolean("proxy_enabled", true).commit();
                ConnectionsManager.setProxySettings(true, proxy.settings);
                notifyChanged(R.string.ProxyResumedAfterVpn);
            }
        }
    }

    private static void notifyChanged(int textRes) {
        FileLog.d("ProxyVpnGuard: " + LocaleController.getString(textRes));
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        if (!ApplicationLoader.mainInterfacePaused) {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.showBulletin, Bulletin.TYPE_SUCCESS, LocaleController.getString(textRes));
        }
    }
}
