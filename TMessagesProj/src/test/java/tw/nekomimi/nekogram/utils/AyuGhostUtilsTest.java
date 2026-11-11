package tw.nekomimi.nekogram.utils;

import org.junit.Test;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

public class AyuGhostUtilsTest {
    private Object state() throws Exception {
        Constructor<?> c = Class.forName(AyuGhostUtils.class.getName() + "$AyuStateVariable").getDeclaredConstructor();
        c.setAccessible(true);
        return c.newInstance();
    }

    private Object call(Object s, String method, Class<?>[] types, Object... args) throws Exception {
        Method m = s.getClass().getDeclaredMethod(method, types);
        m.setAccessible(true);
        return m.invoke(s, args);
    }

    @Test public void playbackGuardsDoNotConsumeExplicitReceiptPermit() throws Exception {
        Object s = state();
        call(s, "set", new Class[]{boolean.class, int.class}, true, 1);
        for (int i = 0; i < 5; i++) assertEquals(true, call(s, "peek", new Class[]{}));
        assertEquals(true, call(s, "process", new Class[]{}));
        assertEquals(false, call(s, "process", new Class[]{}));
        assertEquals(false, call(s, "peek", new Class[]{}));
    }

    @Test public void zeroCountCannotCreateUnlimitedReadPermission() throws Exception {
        Object s = state();
        call(s, "set", new Class[]{boolean.class, int.class}, true, 0);
        assertEquals(false, call(s, "process", new Class[]{}));
        call(s, "set", new Class[]{boolean.class, int.class}, true, -1);
        for (int i = 0; i < 5; i++) assertEquals(true, call(s, "process", new Class[]{}));
        call(s, "set", new Class[]{boolean.class, int.class}, false, -1);
        assertEquals(false, call(s, "process", new Class[]{}));
    }

    @Test public void concurrentTransportsCannotReuseOneShotPermission() throws Exception {
        Object s = state();
        call(s, "set", new Class[]{boolean.class, int.class}, true, 1);
        java.util.concurrent.atomic.AtomicInteger allowed = new java.util.concurrent.atomic.AtomicInteger();
        java.util.ArrayList<Thread> threads = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        for (int i = 0; i < 20; i++) {
            Thread thread = new Thread(() -> {
                try {
                    if ((Boolean) call(s, "process", new Class[]{})) allowed.incrementAndGet();
                } catch (Throwable e) {
                    failure.set(e);
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) thread.join();
        assertNull(failure.get());
        assertEquals(1, allowed.get());
    }
}
