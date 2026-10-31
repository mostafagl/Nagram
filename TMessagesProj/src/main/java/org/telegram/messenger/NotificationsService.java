/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;
import org.telegram.utils.proxy.ProxyVpnGuard;

public class NotificationsService extends Service implements NotificationCenter.NotificationCenterDelegate {
    public static final String ACTION_START_BACKGROUND = "org.telegram.NotificationsService.START_BACKGROUND";
    public static final String ACTION_START_FOREGROUND = "org.telegram.NotificationsService.START_FOREGROUND";
    // Low importance: a silent, collapsed notification. The old default-importance channel
    // can't be changed in place, so it is replaced.
    public static final String CHANNEL_ID = "parvagram_connection_channel";
    private static final String OLD_CHANNEL_ID = "push_service_channel";
    private static final int NOTIFICATION_ID = 9999;

    private boolean foreground;
    private boolean observing;
    private String lastText;

    @Override
    public void onCreate() {
        super.onCreate();
        ApplicationLoader.postInitApplication();
    }

    private void doForegroundThings() {
        createNotificationChannel();
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
            foreground = true;
        } catch (Throwable e) {
            Log.e("TFOSS", "Failed to start push service");
        }
        if (!observing) {
            observing = true;
            AndroidUtilities.runOnUIThread(() -> {
                NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged);
                NotificationCenter.getInstance(UserConfig.selectedAccount).addObserver(this, NotificationCenter.didUpdateConnectionState);
            });
        }
    }

    /** "Connected via V2Ray · name", "Connecting…" and so on, so the user can see the link is up. */
    private String getStatusText() {
        final int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        final SharedConfig.ProxyInfo proxy = SharedConfig.currentProxy;
        final boolean proxyEnabled = proxy != null && MessagesController.getGlobalMainSettings().getBoolean("proxy_enabled", false);
        String via = null;
        if (proxyEnabled) {
            if (proxy.settings.getType() == ProxySettings.Type.V2RAY) {
                via = "V2Ray" + (TextUtils.isEmpty(proxy.settings.getUser()) ? "" : " · " + proxy.settings.getUser());
            } else {
                via = LocaleController.getString(R.string.KeepAliveViaProxy);
            }
        } else if (ProxyVpnGuard.isVpnActive()) {
            via = "VPN";
        }
        switch (state) {
            case ConnectionsManager.ConnectionStateConnected:
            case ConnectionsManager.ConnectionStateUpdating:
                return via == null ? LocaleController.getString(R.string.KeepAliveConnected) : LocaleController.formatString(R.string.KeepAliveConnectedVia, via);
            case ConnectionsManager.ConnectionStateWaitingForNetwork:
                return LocaleController.getString(R.string.WaitingForNetwork);
            case ConnectionsManager.ConnectionStateConnectingToProxy:
                return LocaleController.getString(R.string.ConnectingToProxy);
            default:
                return LocaleController.getString(R.string.Connecting);
        }
    }

    private Notification buildNotification() {
        final String text = getStatusText();
        lastText = text;
        final Intent intent = new Intent(this, org.telegram.ui.LaunchActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        final PendingIntent contentIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setShowWhen(false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setSmallIcon(NotificationsController.getNotificationIconResId())
                .setContentTitle(LocaleController.getString(R.string.KeepAliveTitle))
                .setContentText(text)
                .setContentIntent(contentIntent)
                .build();
    }

    private void updateNotification() {
        if (!foreground) {
            return;
        }
        final String text = getStatusText();
        if (text.equals(lastText)) {
            return;
        }
        try {
            final NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.notify(NOTIFICATION_ID, buildNotification());
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        updateNotification();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            try {
                notificationManager.deleteNotificationChannel(OLD_CHANNEL_ID);
            } catch (Throwable ignore) {
            }
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, LocaleController.getString(R.string.KeepAliveTitle), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        super.onTimeout(startId, fgsType);
        stopSelf();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String type = intent != null ? intent.getAction() : null;
        // The service is restarted by the 10 minute alarm too; only (re)build the notification once.
        // A sticky restart after the process was killed comes without an intent.
        final boolean wantForeground = ACTION_START_FOREGROUND.equals(type)
                || type == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && xyz.nextalone.nagram.NaConfig.INSTANCE.getPushServiceTypeInAppDialog().Bool();
        if (wantForeground && !foreground) {
            doForegroundThings();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void onDestroy() {
        super.onDestroy();
        if (observing) {
            observing = false;
            AndroidUtilities.runOnUIThread(() -> {
                NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxySettingsChanged);
                NotificationCenter.getInstance(UserConfig.selectedAccount).removeObserver(this, NotificationCenter.didUpdateConnectionState);
            });
        }
        foreground = false;
        try {
            stopForeground(true);
        } catch (Throwable ignore) {
        }
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        if (preferences.getBoolean("pushService", true)) {
            Intent intent = new Intent("org.telegram.start");
            intent.setPackage(getPackageName());
            try {
                sendBroadcast(intent);
            } catch (Exception ex) {
                // 辣鷄miui 就你事最多.jpg
            }
        }
    }

}
