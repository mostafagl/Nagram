package tw.nekomimi.nekogram.helpers;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.lang.reflect.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class ContactChangesTest {
    @Test public void oldSnapshotsMigrateAndProfileTextKeepsSeparators() throws Exception {
        Class<?> c = Class.forName(ContactChanges.class.getName() + "$Snapshot");
        Method decode = c.getDeclaredMethod("decode", String.class);
        Method encode = c.getDeclaredMethod("encode");
        decode.setAccessible(true);
        encode.setAccessible(true);
        Object snap = decode.invoke(null, "42\u0001123\u0001username\u0001Name\u00010\u00011");
        assertNotNull(snap);
        Field name = c.getDeclaredField("name");
        name.setAccessible(true);
        name.set(snap, "Name\u0001with separator\nفارسی");
        Object migrated = decode.invoke(null, encode.invoke(snap));
        assertEquals(name.get(snap), name.get(migrated));
        Field bio = c.getDeclaredField("bio");
        bio.setAccessible(true);
        assertNull(bio.get(migrated)); // An unobserved bio is not a deletion.
        bio.set(migrated, "");
        assertEquals("", bio.get(decode.invoke(null, encode.invoke(migrated))));
    }
}
