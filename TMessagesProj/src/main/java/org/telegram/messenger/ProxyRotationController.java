package org.telegram.messenger;

import android.content.SharedPreferences;
import android.os.SystemClock;

import org.telegram.tgnet.ConnectionsManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ProxyRotationController implements NotificationCenter.NotificationCenterDelegate {
    private final static ProxyRotationController INSTANCE = new ProxyRotationController();

    public final static int DEFAULT_TIMEOUT_INDEX = 1;
    private final static int MAX_CHECKS = 25;
    /** After the first working proxy answers, wait this long for faster ones before switching. */
    private final static long BEST_PING_WAIT_MS = 3000;
    private boolean switchScheduled;
    private final Runnable switchRunnable = () -> {
        switchScheduled = false;
        switchToAvailable();
    };
    public final static List<Integer> ROTATION_TIMEOUTS = Arrays.asList(
            5, 10, 15, 30, 60
    );

    private boolean isCurrentlyChecking;
    private Runnable checkProxyAndSwitchRunnable = () -> {
        if (!canRotate() || ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState() != ConnectionsManager.ConnectionStateConnectingToProxy) {
            return;
        }
        isCurrentlyChecking = true;

        int currentAccount = UserConfig.selectedAccount;
        boolean startedCheck = false;
        // Parvagram: with V2Ray subscriptions the list can hold hundreds of configs, and each V2Ray
        // test starts a core. Test the most promising ones first (worked before, lowest ping), at
        // most MAX_CHECKS per round; the next round takes the rest.
        final List<SharedConfig.ProxyInfo> candidates = new ArrayList<>(SharedConfig.proxyList);
        Collections.sort(candidates, (a, b) -> {
            final int ra = a.available ? 0 : a.availableCheckTime == 0 ? 1 : 2;
            final int rb = b.available ? 0 : b.availableCheckTime == 0 ? 1 : 2;
            return ra != rb ? Integer.compare(ra, rb) : Long.compare(a.ping, b.ping);
        });
        int started = 0;
        for (int i = 0; i < candidates.size() && started < MAX_CHECKS; i++) {
            SharedConfig.ProxyInfo proxyInfo = candidates.get(i);
            if (proxyInfo == SharedConfig.currentProxy || proxyInfo.checking || proxyInfo.availableCheckTime != 0 && SystemClock.elapsedRealtime() - proxyInfo.availableCheckTime < 2 * 60 * 1000) {
                continue;
            }
            started++;
            startedCheck = true;
            proxyInfo.checking = true;
            ConnectionsManager.getInstance(currentAccount).checkProxy(proxyInfo.settings, time -> AndroidUtilities.runOnUIThread(() -> {
                proxyInfo.availableCheckTime = SystemClock.elapsedRealtime();
                proxyInfo.checking = false;
                if (time == -1) {
                    proxyInfo.available = false;
                    proxyInfo.ping = 0;
                } else {
                    proxyInfo.ping = time;
                    proxyInfo.available = true;
                }
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyCheckDone, proxyInfo);
            }));
        }

        if (!startedCheck) {
            isCurrentlyChecking = false;
            switchToAvailable();
        }
    };

    public static void init() {
        INSTANCE.initInternal();
    }

    @SuppressWarnings("ComparatorCombinators")
    private void switchToAvailable() {
        if (!canRotate() || ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState() != ConnectionsManager.ConnectionStateConnectingToProxy) {
            isCurrentlyChecking = false;
            return;
        }

        List<SharedConfig.ProxyInfo> sortedList = new ArrayList<>(SharedConfig.proxyList);
        Collections.sort(sortedList, (o1, o2) -> Long.compare(o1.ping, o2.ping));
        for (SharedConfig.ProxyInfo info : sortedList) {
            if (info == SharedConfig.currentProxy || info.checking || !info.available) {
                continue;
            }

            SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
            editor.putBoolean("proxy_enabled", true);
            info.settings.toSharedPreferences(editor);
            editor.apply();

            SharedConfig.currentProxy = info;
            isCurrentlyChecking = false;
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyChangedByRotation);
            ConnectionsManager.setProxySettings(true, SharedConfig.currentProxy.settings);
            return;
        }

        // A failed check must not discard later successful results from this batch.
        isCurrentlyChecking = false;
        for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
            if (info.checking) {
                isCurrentlyChecking = true;
                break;
            }
        }
        if (!isCurrentlyChecking) {
            // Nothing in this round worked: test the next configs after the usual wait.
            AndroidUtilities.cancelRunOnUIThread(checkProxyAndSwitchRunnable);
            AndroidUtilities.runOnUIThread(checkProxyAndSwitchRunnable, ROTATION_TIMEOUTS.get(SharedConfig.proxyRotationTimeout) * 1000L);
        }
    }

    private void initInternal() {
        for (int i : SharedConfig.activeAccounts) {
            NotificationCenter.getInstance(i).addObserver(this, NotificationCenter.didUpdateConnectionState);
        }
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxyCheckDone);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged);
        // The initial connection state may predate observer registration.
        AndroidUtilities.runOnUIThread(this::updateConnectionState);
    }

    private boolean canRotate() {
        return SharedConfig.isProxyEnabled() && SharedConfig.proxyRotationEnabled && SharedConfig.proxyList.size() > 1;
    }

    private void updateConnectionState() {
        AndroidUtilities.cancelRunOnUIThread(checkProxyAndSwitchRunnable);
        if (!canRotate()) {
            AndroidUtilities.cancelRunOnUIThread(switchRunnable);
            switchScheduled = false;
            isCurrentlyChecking = false;
            return;
        }

        int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        if (state == ConnectionsManager.ConnectionStateConnectingToProxy) {
            if (!isCurrentlyChecking) {
                AndroidUtilities.runOnUIThread(checkProxyAndSwitchRunnable, ROTATION_TIMEOUTS.get(SharedConfig.proxyRotationTimeout) * 1000L);
            }
        } else {
            isCurrentlyChecking = false;
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.proxyCheckDone) {
            if (!SharedConfig.isProxyEnabled() || !SharedConfig.proxyRotationEnabled || SharedConfig.proxyList.size() <= 1 || !isCurrentlyChecking) {
                return;
            }

            // Pick the lowest ping, not just the first answer: once one works, give the others a
            // moment (or switch now if every test of this round is done).
            boolean anyChecking = false;
            boolean anyAvailable = false;
            for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
                anyChecking |= info.checking;
                anyAvailable |= info.available && info != SharedConfig.currentProxy;
            }
            if (!anyChecking) {
                AndroidUtilities.cancelRunOnUIThread(switchRunnable);
                switchScheduled = false;
                switchToAvailable();
            } else if (anyAvailable && !switchScheduled) {
                switchScheduled = true;
                AndroidUtilities.runOnUIThread(switchRunnable, BEST_PING_WAIT_MS);
            }
        } else if (id == NotificationCenter.proxySettingsChanged) {
            updateConnectionState();
        } else if (id == NotificationCenter.didUpdateConnectionState && account == UserConfig.selectedAccount) {
            updateConnectionState();
        }
    }
}
