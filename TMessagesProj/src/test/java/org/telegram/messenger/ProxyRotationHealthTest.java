package org.telegram.messenger;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProxyRotationHealthTest {
    @Test public void reliableTelegramConnectionOutranksSingleFastPingAfterFailure() {
        ProxyRotationController.ProxyHealth stable = new ProxyRotationController.ProxyHealth();
        stable.checked(300, 1000);
        stable.connected(1000);
        ProxyRotationController.ProxyHealth flaky = new ProxyRotationController.ProxyHealth();
        flaky.checked(10, 1000);
        flaky.failed(2000);
        flaky.checked(5, 3000);
        assertTrue(stable.score(300) < flaky.score(5));
        assertEquals(32_000, flaky.cooldownUntil);
        assertEquals(1, flaky.consecutiveFailures);
    }

    @Test public void failuresBackOffAndRealConnectionResetsFailurePenalty() {
        ProxyRotationController.ProxyHealth h = new ProxyRotationController.ProxyHealth();
        h.checked(100, 0);
        h.checked(500, 0);
        assertEquals(200, h.latency);
        for (int i = 0; i < 10; i++) h.failed(1000);
        assertEquals(121_000, h.cooldownUntil);
        h.connected(450);
        assertEquals(0, h.consecutiveFailures);
        assertEquals(0, h.cooldownUntil);
        assertEquals(450, h.recoveryMs);
    }
}
