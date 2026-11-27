package org.telegram.messenger;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.test.core.app.ApplicationProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.telegram.tgnet.RequestTimeDelegate;
import org.telegram.utils.proxy.ProxySettings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class ManualProxyChecksTest {
    private final Map<SharedConfig.ProxyInfo, RequestTimeDelegate> callbacks = new IdentityHashMap<>();
    private final ArrayList<SharedConfig.ProxyInfo> started = new ArrayList<>();

    private static final class Result implements ProxyRotationController.ManualCheckListener {
        List<SharedConfig.ProxyInfo> measured;
        int unavailable, completed, progress, total;
        @Override public void onProgress(int done, int total) { progress = done; this.total = total; }
        @Override public void onComplete(List<SharedConfig.ProxyInfo> measured, int unavailable) {
            this.measured = measured;
            this.unavailable = unavailable;
            completed++;
        }
    }

    @Before public void setup() {
        // Avoid loading the full Telegram app/native account stack in a scheduler policy test.
        ApplicationLoader.applicationContext = null;
        SharedConfig.proxyList.clear();
        SharedConfig.currentProxy = null;
        Context context = ApplicationProvider.getApplicationContext();
        ApplicationLoader.applicationContext = context;
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
        ProxyRotationController.manualPreferences = context.getSharedPreferences("manual_proxy_test", Context.MODE_PRIVATE);
        ProxyRotationController.manualPreferences.edit().clear().commit();
        ProxyRotationController.manualProxyChecker = (info, callback) -> {
            started.add(info);
            callbacks.put(info, callback);
        };
    }

    @After public void cleanup() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(7));
        ProxyRotationController.manualProxyChecker = null;
        ProxyRotationController.manualPreferences = null;
        ApplicationLoader.applicationHandler.removeCallbacksAndMessages(null);
        SharedConfig.proxyList.clear();
        SharedConfig.currentProxy = null;
    }

    private SharedConfig.ProxyInfo proxy(int port) {
        SharedConfig.ProxyInfo info = new SharedConfig.ProxyInfo(ProxySettings.builder()
                .setType(ProxySettings.Type.SOCKS5).setAddress("192.0.2.1").setPort(port).build());
        SharedConfig.proxyList.add(info);
        return info;
    }

    private void answer(SharedConfig.ProxyInfo info, long delay) {
        RequestTimeDelegate callback = callbacks.remove(info);
        assertNotNull(callback);
        callback.run(delay);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2));
    }

    @Test public void foreignCheckIsWaitedThenMeasuredWithoutMutatingCallerScope() {
        SharedConfig.ProxyInfo info = proxy(1080);
        info.checking = true;
        List<SharedConfig.ProxyInfo> request = new ArrayList<>(SharedConfig.proxyList);
        Result result = new Result();
        assertTrue(ProxyRotationController.checkManually(request, result));
        assertEquals(0, result.completed);
        assertTrue(started.isEmpty());
        assertEquals(1, request.size());
        info.checking = false;
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        assertEquals(1, started.size());
        answer(info, 55);
        assertEquals(1, request.size());
        assertEquals(1, result.completed);
        assertEquals(1, result.progress);
        assertEquals(1, result.total);
        assertEquals(info, result.measured.get(0));
    }

    @Test public void deniedDirectRouteDoesNotEraseOldPingOrBecomeDeletionEvidence() {
        SharedConfig.ProxyInfo info = proxy(1081);
        info.available = true;
        info.ping = 123;
        info.availableCheckTime = 99;
        Result result = new Result();
        assertTrue(ProxyRotationController.checkManually(new ArrayList<>(SharedConfig.proxyList), result));
        answer(info, -2);
        assertEquals(1, result.completed);
        assertEquals(1, result.unavailable);
        assertTrue(result.measured.isEmpty());
        assertTrue(info.available);
        assertEquals(123, info.ping);
        assertEquals(99, info.availableCheckTime);
        assertFalse(info.checking);
    }

    @Test public void concurrencyIsBoundedAndSecondOperationCannotStealTheBatch() {
        for (int i = 0; i < 8; i++) proxy(1100 + i);
        Result result = new Result(), competing = new Result();
        assertTrue(ProxyRotationController.checkManually(new ArrayList<>(SharedConfig.proxyList), result));
        assertEquals(4, started.size());
        assertFalse(ProxyRotationController.checkManually(new ArrayList<>(SharedConfig.proxyList), competing));
        for (int i = 0; i < 8; i++) answer(started.get(i), 50 + i);
        assertEquals(8, started.size());
        assertEquals(8, result.measured.size());
        assertEquals(1, result.completed);
        assertEquals(0, competing.completed);
    }

    @Test public void watchdogFinishesOnceAndLateCallbackCannotOverwriteNewerState() {
        SharedConfig.ProxyInfo info = proxy(1082);
        Result result = new Result();
        assertTrue(ProxyRotationController.checkManually(new ArrayList<>(SharedConfig.proxyList), result));
        RequestTimeDelegate late = callbacks.get(info);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(6).plusMillis(1));
        assertEquals(1, result.completed);
        assertEquals(1, result.unavailable);
        assertTrue(result.measured.isEmpty());
        assertFalse(info.checking);
        info.ping = 200;
        info.available = true;
        late.run(1);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2));
        assertEquals(200, info.ping);
        assertEquals(1, result.completed);
    }

    @Test public void changedManualSelectionPreventsAutomaticConnectionToBatchWinner() {
        SharedConfig.ProxyInfo candidate = proxy(1083), chosenByUser = proxy(1084);
        Result result = new Result();
        assertTrue(ProxyRotationController.checkManually(List.of(candidate), result));
        SharedConfig.currentProxy = chosenByUser;
        answer(candidate, 1);
        assertFalse(ProxyRotationController.connectLowestMeasured(result.measured));
        assertSame(chosenByUser, SharedConfig.currentProxy);
    }

    @Test public void changedPreferenceInvalidatesSelectionEvenIfProxyIdentityIsUnchanged() {
        SharedConfig.ProxyInfo candidate = proxy(1085);
        Result result = new Result();
        assertTrue(ProxyRotationController.checkManually(List.of(candidate), result));
        ProxyRotationController.manualPreferences.edit().putBoolean("proxy_enabled", true).commit();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2));
        answer(candidate, 10);
        assertFalse(ProxyRotationController.connectLowestMeasured(result.measured));
        assertNull(SharedConfig.currentProxy);
    }

    @Test public void emptyScopeCannotPretendToHaveAWinner() {
        Result result = new Result();
        assertTrue(ProxyRotationController.checkManually(new ArrayList<>(), result));
        assertEquals(1, result.completed);
        assertTrue(result.measured.isEmpty());
        assertTrue(started.isEmpty());
        assertFalse(ProxyRotationController.connectLowestMeasured(result.measured));
    }
    @Test public void editingConfigDuringTestCannotApplyOldPingToNewServer() {
        SharedConfig.ProxyInfo info = proxy(1086);
        Result result = new Result();
        assertTrue(ProxyRotationController.checkManually(List.of(info), result));
        info.settings = ProxySettings.builder().setType(ProxySettings.Type.SOCKS5)
                .setAddress("192.0.2.2").setPort(1086).build();
        info.ping = 500;
        info.checking = true; // The edited configuration may already own a newer check.
        answer(info, 1);
        assertEquals(1, result.unavailable);
        assertTrue(result.measured.isEmpty());
        assertEquals(500, info.ping);
        assertTrue(info.checking);
    }

    @Test public void duplicateTransportCallbacksAreAcceptedOnlyOnce() {
        SharedConfig.ProxyInfo info = proxy(1087);
        Result result = new Result();
        assertTrue(ProxyRotationController.checkManually(List.of(info), result));
        RequestTimeDelegate callback = callbacks.get(info);
        callback.run(100);
        callback.run(1);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2));
        assertEquals(1, result.completed);
        assertEquals(1, result.progress);
        assertEquals(100, info.ping);
        assertEquals(1, result.measured.size());
    }

    @Test public void checkerStartupFailureIsUnavailableRatherThanADeadServer() {
        SharedConfig.ProxyInfo info = proxy(1088);
        info.ping = 80;
        info.available = true;
        ProxyRotationController.manualProxyChecker = (proxy, callback) -> { throw new IllegalStateException("transport unavailable"); };
        Result result = new Result();
        assertTrue(ProxyRotationController.checkManually(List.of(info), result));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2));
        assertEquals(1, result.completed);
        assertEquals(1, result.unavailable);
        assertTrue(result.measured.isEmpty());
        assertTrue(info.available);
        assertEquals(80, info.ping);
        assertFalse(info.checking);
    }

}
