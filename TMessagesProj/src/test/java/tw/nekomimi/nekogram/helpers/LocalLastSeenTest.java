package tw.nekomimi.nekogram.helpers;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import java.lang.reflect.Field;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 28)
public class LocalLastSeenTest {
    @Before public void reset() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        ApplicationLoader.applicationContext = context;
        context.getSharedPreferences("parva_last_seen", Context.MODE_PRIVATE).edit().clear().commit();
        Field f = LocalLastSeen.class.getDeclaredField("cache");
        f.setAccessible(true);
        ((Map<?, ?>) f.get(null)).clear();
    }

    @Test public void lateEventsDoNotMoveLatestTimeBackwardsAndAccountsStaySeparate() {
        LocalLastSeen.record(0, 123456, 200);
        LocalLastSeen.record(0, 123456, 100);
        LocalLastSeen.record(1, 123456, 50);
        assertEquals(200, LocalLastSeen.getTime(0, 123456));
        assertEquals(50, LocalLastSeen.getTime(1, 123456));
        LocalLastSeen.clearAccount(0);
        assertEquals(0, LocalLastSeen.getTime(0, 123456));
        assertEquals(50, LocalLastSeen.getTime(1, 123456));
    }

    @Test public void removesOldActivityHistoryWithoutLosingLatestTimestamp() {
        Context context = ApplicationProvider.getApplicationContext();
        android.content.SharedPreferences prefs = context.getSharedPreferences("parva_last_seen", Context.MODE_PRIVATE);
        prefs.edit().putString("history_0", "old activity and receipt events").putInt("0:123456", 200).commit();
        LocalLastSeen.isEnabled();
        assertFalse(prefs.contains("history_0"));
        assertEquals(200, LocalLastSeen.getTime(0, 123456));
        LocalLastSeen.setEnabled(false);
        LocalLastSeen.record(0, 123456, 300);
        assertEquals(200, LocalLastSeen.getTime(0, 123456));
    }
}
