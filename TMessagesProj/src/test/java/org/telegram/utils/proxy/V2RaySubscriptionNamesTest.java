package org.telegram.utils.proxy;

import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 28)
public class V2RaySubscriptionNamesTest {
    private static final String FIRST = "vless://00000000-0000-0000-0000-000000000001@example.com:443?security=tls#🇩🇪 My server 1";
    private static final String SECOND = "trojan://password@example.org:443#Finland%20server";

    @Test public void plainAndBase64SubscriptionsPreserveWholeNamesAndSeparateLines() {
        String body = FIRST + "\n" + SECOND + "\n" + FIRST;
        assertNames(V2RaySubscriptions.parseLinks(body));
        assertNames(V2RaySubscriptions.parseLinks(Base64.encodeToString(body.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP)));
    }

    private void assertNames(List<String> links) {
        assertEquals(2, links.size());
        assertEquals("🇩🇪 My server 1", V2RayConfig.parse(links.get(0)).remark);
        assertEquals("Finland server", V2RayConfig.parse(links.get(1)).remark);
    }
}
