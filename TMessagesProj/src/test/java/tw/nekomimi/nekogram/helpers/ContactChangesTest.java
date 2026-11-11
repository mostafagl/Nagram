package tw.nekomimi.nekogram.helpers;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.lang.reflect.*;
import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import org.telegram.messenger.ApplicationLoader;
import java.io.File;
import java.io.RandomAccessFile;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class ContactChangesTest {
    @Test public void cleanupHonorsRetentionQuotaAndArchiveFileBoundary() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        ApplicationLoader.applicationContext = context;
        context.getSharedPreferences("parva_contact_changes", Context.MODE_PRIVATE).edit()
                .putInt("archive_days", 30).putInt("archive_mb", 16).commit();
        ContactChanges changes = ContactChanges.getInstance(0);
        File dir = changes.archiveDirectory();
        dir.mkdirs();
        File[] prior = dir.listFiles();
        if (prior != null) for (File f : prior) f.delete();
        long now = System.currentTimeMillis();
        File expired = new File(dir, "123_1.jpg");
        expired.createNewFile();
        expired.setLastModified(now - 31L * 86_400_000);
        File unrelated = new File(dir, "unrelated.jpg");
        unrelated.createNewFile();
        File partial = new File(dir, "123_9.jpg.tmp");
        partial.createNewFile();
        for (int i = 2; i <= 4; i++) {
            File file = new File(dir, "123_" + i + ".jpg");
            try (RandomAccessFile out = new RandomAccessFile(file, "rw")) { out.setLength(9 * 1024 * 1024); }
            file.setLastModified(now - (5 - i) * 1000);
        }
        context.getSharedPreferences("parva_contact_changes_0", Context.MODE_PRIVATE).edit()
                .putString("photo_meta_123_1.jpg", "expired").putString("photo_meta_123_99.jpg", "orphan").commit();
        Method clean = ContactChanges.class.getDeclaredMethod("cleanArchive");
        clean.setAccessible(true);
        clean.invoke(changes);
        assertFalse(expired.exists());
        assertFalse(partial.exists());
        assertTrue(unrelated.exists());
        assertEquals(1, changes.getArchivedPhotos(123).length);
        assertTrue(new File(dir, "123_4.jpg").exists());
        assertFalse(context.getSharedPreferences("parva_contact_changes_0", Context.MODE_PRIVATE).contains("photo_meta_123_99.jpg"));
        for (File f : dir.listFiles()) f.delete();
    }

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
