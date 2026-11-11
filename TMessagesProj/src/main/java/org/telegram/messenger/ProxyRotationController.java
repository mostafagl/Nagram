package org.telegram.messenger;

import android.content.SharedPreferences;
import android.os.SystemClock;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;
import org.telegram.utils.proxy.V2RayTransport;

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
    private static final long MIN_SWITCH_INTERVAL_MS = 30_000;
    private static final long FRESH_CHECK_MS = 120_000;
    private final java.util.IdentityHashMap<SharedConfig.ProxyInfo, ProxyHealth> health = new java.util.IdentityHashMap<>();
    private final java.util.HashSet<SharedConfig.ProxyInfo> batch = new java.util.HashSet<>();
    private long generation, lastSwitchTime, failureStarted;
    private SharedConfig.ProxyInfo failedProxy, observedProxy;
    private int observedAccount = -1;
    private boolean initialized;
    private boolean launchingChecks;
    private final Runnable batchTimeout = () -> {
        if (!this.isCurrentlyChecking) return;
        invalidateBatch();
        switchToAvailable();
    };

    /** Session health; latency is EWMA, while real Telegram connection failures weigh more. */
    static final class ProxyHealth {
        long latency, cooldownUntil, recoveryMs;
        int checkSuccesses, connectionSuccesses, consecutiveFailures;

        void checked(long ping, long now) {
            if (ping <= 0) {
                failed(now);
            } else {
                latency = latency == 0 ? ping : (latency * 3 + ping) / 4;
                checkSuccesses = Math.min(5, checkSuccesses + 1);
                // A ping alone does not undo an actual Telegram connection failure.
            }
        }

        void failed(long now) {
            consecutiveFailures = Math.min(10, consecutiveFailures + 1);
            cooldownUntil = now + Math.min(120_000L, consecutiveFailures * 30_000L);
        }

        void connected(long recovery) {
            connectionSuccesses = Math.min(5, connectionSuccesses + 1);
            consecutiveFailures = 0;
            cooldownUntil = 0;
            recoveryMs = recovery;
        }

        long score(long ping) {
            long base = latency > 0 ? latency : ping > 0 ? ping : 10_000;
            return base + consecutiveFailures * 10_000L + Math.min(recoveryMs, 30_000) / 10
                    + (5 - checkSuccesses) * 100L + (5 - connectionSuccesses) * 1000L;
        }
    }

    private ProxyHealth healthOf(SharedConfig.ProxyInfo info) {
        return health.computeIfAbsent(info, ignored -> new ProxyHealth());
    }

    private long rotationDelay() {
        int index = Math.max(0, Math.min(SharedConfig.proxyRotationTimeout, ROTATION_TIMEOUTS.size() - 1));
        return ROTATION_TIMEOUTS.get(index) * 1000L;
    }

    private boolean usable(SharedConfig.ProxyInfo info) {
        long now = SystemClock.elapsedRealtime();
        return info != SharedConfig.currentProxy && !info.checking && info.available && info.ping > 0
                && info.availableCheckTime > 0 && now - info.availableCheckTime <= FRESH_CHECK_MS
                && now >= healthOf(info).cooldownUntil;
    }

    private void invalidateBatch() {
        generation++;
        batch.clear();
        AndroidUtilities.cancelRunOnUIThread(switchRunnable);
        AndroidUtilities.cancelRunOnUIThread(batchTimeout);
        switchScheduled = false;
        isCurrentlyChecking = false;
    }
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
        long now = SystemClock.elapsedRealtime();
        if (lastSwitchTime != 0 && now - lastSwitchTime < MIN_SWITCH_INTERVAL_MS) {
            AndroidUtilities.runOnUIThread(this.checkProxyAndSwitchRunnable, MIN_SWITCH_INTERVAL_MS - (now - lastSwitchTime));
            return;
        }
        if (SharedConfig.currentProxy != null && failedProxy != SharedConfig.currentProxy) {
            failedProxy = SharedConfig.currentProxy;
            healthOf(failedProxy).failed(now);
            failedProxy.available = false;
        }
        health.keySet().retainAll(SharedConfig.proxyList);
        final long batchGeneration = ++generation;
        batch.clear();
        isCurrentlyChecking = true;
        AndroidUtilities.cancelRunOnUIThread(batchTimeout);
        AndroidUtilities.runOnUIThread(batchTimeout, 60_000);

        int currentAccount = UserConfig.selectedAccount;
        boolean startedCheck = false;
        // Parvagram: with V2Ray subscriptions the list can hold hundreds of configs, and each V2Ray
        // test starts a core. Test the most promising ones first (worked before, lowest ping), at
        // most MAX_CHECKS per round; the next round takes the rest.
        final List<SharedConfig.ProxyInfo> candidates = new ArrayList<>(SharedConfig.proxyList);
        Collections.sort(candidates, (a, b) -> {
            final int ra = a.available ? 0 : a.availableCheckTime == 0 ? 1 : 2;
            final int rb = b.available ? 0 : b.availableCheckTime == 0 ? 1 : 2;
            return ra != rb ? Integer.compare(ra, rb) : Long.compare(healthOf(a).score(a.ping), healthOf(b).score(b.ping));
        });
        int started = 0;
        launchingChecks = true;
        for (int i = 0; i < candidates.size() && started < MAX_CHECKS; i++) {
            SharedConfig.ProxyInfo proxyInfo = candidates.get(i);
            if (proxyInfo == SharedConfig.currentProxy || proxyInfo.checking || now < healthOf(proxyInfo).cooldownUntil
                    || proxyInfo.availableCheckTime != 0 && now - proxyInfo.availableCheckTime < FRESH_CHECK_MS) {
                continue;
            }
            started++;
            startedCheck = true;
            proxyInfo.checking = true;
            batch.add(proxyInfo);
            ConnectionsManager.getInstance(currentAccount).checkProxy(proxyInfo.settings, time -> AndroidUtilities.runOnUIThread(() -> {
                proxyInfo.availableCheckTime = SystemClock.elapsedRealtime();
                proxyInfo.checking = false;
                if (batchGeneration == generation && SharedConfig.proxyList.contains(proxyInfo)) {
                    healthOf(proxyInfo).checked(time, proxyInfo.availableCheckTime);
                }
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
        launchingChecks = false;

        if (!startedCheck) {
            isCurrentlyChecking = false;
            switchToAvailable();
        } else if (!batch.isEmpty()) {
            // Some transports can complete synchronously while the batch is still being built.
            didReceivedNotification(NotificationCenter.proxyCheckDone, currentAccount, batch.iterator().next());
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
        Collections.sort(sortedList, (o1, o2) -> Long.compare(healthOf(o1).score(o1.ping), healthOf(o2).score(o2.ping)));
        for (SharedConfig.ProxyInfo info : sortedList) {
            if (!usable(info)) {
                continue;
            }
            if (!canRotate()) return;
            // A failed replacement core must not tear down the previous running core.
            if (info.settings.getType() == ProxySettings.Type.V2RAY && V2RayTransport.start(info.settings.getSecret()) == 0) {
                info.available = false;
                healthOf(info).failed(SystemClock.elapsedRealtime());
                continue;
            }

            SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
            editor.putBoolean("proxy_enabled", true);
            info.settings.toSharedPreferences(editor);
            editor.apply();

            SharedConfig.currentProxy = info;
            lastSwitchTime = SystemClock.elapsedRealtime();
            failureStarted = lastSwitchTime;
            invalidateBatch();
            isCurrentlyChecking = false;
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyChangedByRotation,
                    LocaleController.getString(R.string.SmartProxySwitchReason));
            ConnectionsManager.setProxySettings(true, SharedConfig.currentProxy.settings);
            return;
        }

        // A failed check must not discard later successful results from this batch.
        isCurrentlyChecking = false;
        for (SharedConfig.ProxyInfo info : batch) {
            if (info.checking) {
                isCurrentlyChecking = true;
                break;
            }
        }
        if (!isCurrentlyChecking) {
            // Nothing in this round worked: test the next configs after the usual wait.
            AndroidUtilities.cancelRunOnUIThread(checkProxyAndSwitchRunnable);
            AndroidUtilities.runOnUIThread(checkProxyAndSwitchRunnable, rotationDelay());
        }
    }

    private void initInternal() {
        if (initialized) return;
        initialized = true;
        // Include slots activated after startup without creating extra rotation controllers.
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            NotificationCenter.getInstance(i).addObserver(this, NotificationCenter.didUpdateConnectionState);
        }
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxyCheckDone);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged);
        // The initial connection state may predate observer registration.
        AndroidUtilities.runOnUIThread(this::updateConnectionState);
    }

    private boolean canRotate() {
        return !org.telegram.utils.proxy.ProxyVpnGuard.isSuspended()
                && SharedConfig.isProxyEnabled() && SharedConfig.proxyRotationEnabled && SharedConfig.proxyList.size() > 1;
    }

    private void updateConnectionState() {
        AndroidUtilities.cancelRunOnUIThread(checkProxyAndSwitchRunnable);
        if (!canRotate()) {
            invalidateBatch();
            return;
        }

        if (observedProxy != SharedConfig.currentProxy || observedAccount != UserConfig.selectedAccount) {
            invalidateBatch();
            observedProxy = SharedConfig.currentProxy;
            observedAccount = UserConfig.selectedAccount;
            failedProxy = null;
            failureStarted = 0;
        }

        int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        if (state == ConnectionsManager.ConnectionStateConnectingToProxy) {
            if (failureStarted == 0) failureStarted = SystemClock.elapsedRealtime();
            if (!isCurrentlyChecking) {
                AndroidUtilities.runOnUIThread(checkProxyAndSwitchRunnable, rotationDelay());
            }
        } else {
            invalidateBatch();
            if (state == ConnectionsManager.ConnectionStateConnected && SharedConfig.currentProxy != null
                    && (failureStarted != 0 || healthOf(SharedConfig.currentProxy).connectionSuccesses == 0)) {
                healthOf(SharedConfig.currentProxy).connected(failureStarted == 0 ? 0 : Math.max(0, SystemClock.elapsedRealtime() - failureStarted));
                failureStarted = 0;
                failedProxy = null;
            }
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.proxyCheckDone) {
            if (launchingChecks) return;
            if (args.length == 0 || !batch.contains(args[0])) return;
            if (!SharedConfig.isProxyEnabled() || !SharedConfig.proxyRotationEnabled || SharedConfig.proxyList.size() <= 1 || !isCurrentlyChecking) {
                return;
            }

            // Pick the lowest ping, not just the first answer: once one works, give the others a
            // moment (or switch now if every test of this round is done).
            boolean anyChecking = false;
            boolean anyAvailable = false;
            for (SharedConfig.ProxyInfo info : batch) {
                anyChecking |= info.checking;
                anyAvailable |= usable(info);
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
