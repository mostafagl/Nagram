package org.telegram.utils.proxy;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.RequestTimeDelegate;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;

/**
 * Runs an Xray-core instance for a V2Ray share link and exposes it as a SOCKS5 proxy on
 * 127.0.0.1, so that tgnet can connect through it like through a regular SOCKS5 proxy.
 */
public final class V2RayTransport {

    private static final String DELAY_TEST_URL = "https://www.gstatic.com/generate_204";

    // Delay tests start a separate, inbound-less core each, so they never touch the running
    // connection. A few run in parallel so that testing a long list doesn't take minutes.
    private static final ExecutorService testExecutor = Executors.newFixedThreadPool(4, r -> {
        final Thread thread = new Thread(r, "v2ray-delay-test");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private static final Object lock = new Object();
    private static boolean initialized;
    private static CoreController controller;
    private static String runningLink;
    private static int runningPort;

    private V2RayTransport() {
    }

    /** @return local SOCKS5 port, or 0 if the core could not be started. */
    public static int start(String link) {
        final V2RayConfig config = V2RayConfig.parse(link);
        if (config == null) {
            return 0;
        }
        synchronized (lock) {
            if (controller != null && controller.getIsRunning() && link.equals(runningLink)) {
                return runningPort;
            }
            stopInternal();
            try {
                ensureInitialized();
                final int port = findFreePort();
                controller = Libv2ray.newCoreController(new Callback());
                controller.startLoop(config.buildConfig(port), 0);
                runningLink = link;
                runningPort = port;
                return port;
            } catch (Throwable e) {
                FileLog.e(e);
                stopInternal();
                return 0;
            }
        }
    }

    /** Whether a core is currently running for the active V2Ray proxy. */
    public static boolean isRunning() {
        synchronized (lock) {
            return controller != null && controller.getIsRunning();
        }
    }

    public static void stop() {
        synchronized (lock) {
            stopInternal();
        }
    }

    /**
     * Blocking delay test through {@code config}, in ms, or -1 when the server doesn't answer.
     * Uses a separate core without inbounds, so it can run next to the active connection.
     */
    public static long measureDelaySync(V2RayConfig config) {
        try {
            ensureInitialized();
            final long time = Libv2ray.measureOutboundDelay(config.buildConfig(findFreePort()), DELAY_TEST_URL);
            return time > 0 ? time : -1;
        } catch (Throwable e) {
            return -1;
        }
    }

    /** Local SOCKS5 port of the running core, or 0. */
    public static int getRunningPort() {
        synchronized (lock) {
            return controller != null && controller.getIsRunning() ? runningPort : 0;
        }
    }

    /** Measures the round-trip time of an HTTP request made through the server in {@code link}. */
    public static void checkDelay(String link, RequestTimeDelegate delegate) {
        final V2RayConfig config = V2RayConfig.parse(link);
        if (config == null) {
            delegate.run(-1);
            return;
        }
        testExecutor.execute(() -> {
            long time;
            try {
                ensureInitialized();
                time = Libv2ray.measureOutboundDelay(config.buildConfig(findFreePort()), DELAY_TEST_URL);
            } catch (Throwable e) {
                FileLog.e(e);
                time = -1;
            }
            delegate.run(time > 0 ? time : -1);
        });
    }

    private static void stopInternal() {
        if (controller != null) {
            try {
                controller.stopLoop();
            } catch (Throwable e) {
                FileLog.e(e);
            }
            controller = null;
        }
        runningLink = null;
        runningPort = 0;
    }

    private static void ensureInitialized() {
        synchronized (lock) {
            if (!initialized) {
                Libv2ray.initCoreEnv(ApplicationLoader.applicationContext.getFilesDir().getAbsolutePath(), "");
                initialized = true;
            }
        }
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static final class Callback implements CoreCallbackHandler {
        @Override
        public long onEmitStatus(long code, String message) {
            FileLog.d("v2ray: " + message);
            return 0;
        }

        @Override
        public long shutdown() {
            return 0;
        }

        @Override
        public long startup() {
            return 0;
        }
    }
}
