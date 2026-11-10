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
}
