package org.telegram.utils.proxy;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.PushListenerController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.AlertDialog;

/**
 * Settings for keeping Telegram's connection (and the V2Ray core, which runs in this process)
 * alive in the background. This is the existing in-app push service ("pushService": a
 * foreground service with a persistent notification) plus the push connection
 * ("pushConnection"), switched on together.
 */
public final class ConnectionKeepAlive {

    private ConnectionKeepAlive() {
    }

    /** On by default when there is no working push provider, i.e. with the in-app push type. */
    public static boolean isDefaultEnabled() {
        return !PushListenerController.getProvider().hasServices();
    }

    public static boolean isEnabled() {
        return MessagesController.getNotificationsSettings(UserConfig.selectedAccount).getBoolean("pushService", isDefaultEnabled());
    }

    public static void setEnabled(boolean enabled) {
        MessagesController.getGlobalNotificationsSettings().edit()
                .putBoolean("pushService", enabled)
                .putBoolean("pushConnection", enabled)
                .apply();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.isValidAccount(a)) {
                continue;
            }
            MessagesController.getNotificationsSettings(a).edit()
                    .putBoolean("pushService", enabled)
                    .putBoolean("pushConnection", enabled)
                    .apply();
            ConnectionsManager.getInstance(a).setPushConnectionEnabled(enabled);
        }
        ApplicationLoader.startPushService();
    }

    public static boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        try {
            final PowerManager pm = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
            return pm.isIgnoringBatteryOptimizations(ApplicationLoader.applicationContext.getPackageName());
        } catch (Throwable e) {
            return true;
        }
    }

    /** Explains why, then opens the system prompt that exempts the app from battery optimization. */
    public static void requestBatteryExemption(Activity activity) {
        if (activity == null || isIgnoringBatteryOptimizations()) {
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.ProxyBatteryTitle))
                .setMessage(LocaleController.getString(R.string.ProxyBatteryText))
                .setPositiveButton(LocaleController.getString(R.string.ProxyBatteryAllow), (d, w) -> {
                    try {
                        final Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        intent.setData(Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(intent);
                    } catch (Throwable e) {
                        try {
                            activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                        } catch (Throwable e2) {
                            FileLog.e(e2);
                        }
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }
}
