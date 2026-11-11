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
@Config(application = android.app.Application.class)
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
        LocalLastSeen.record(0, 123456, 200, LocalLastSeen.MESSAGE);
        LocalLastSeen.record(0, 123456, 100, LocalLastSeen.OFFLINE);
        LocalLastSeen.record(1, 123456, 50, LocalLastSeen.MESSAGE);
        assertEquals(200, LocalLastSeen.getTime(0, 123456));
        assertEquals(50, LocalLastSeen.getTime(1, 123456));
        assertEquals(2, LocalLastSeen.getHistory(0, 123456).size());
    }

    @Test public void readWatermarksPreserveRangeAndIgnoreDuplicateOrRegressiveUpdates() {
        LocalLastSeen.recordRead(0, 123456, 10, 20, 100);
        LocalLastSeen.recordRead(0, 123456, 20, 20, 101);
        LocalLastSeen.recordRead(0, 123456, 20, 19, 102);
        assertEquals(1, LocalLastSeen.getHistory(0, 123456).size());
        LocalLastSeen.Event e = LocalLastSeen.getHistory(0, 123456).get(0);
        assertEquals(11, e.fromMessageId);
        assertEquals(20, e.maxMessageId);
        assertTrue(e.observedAt > 0);
    }

    @Test public void disabledHistoryDoesNotCollectAndRetentionIsBounded() {
        LocalLastSeen.setEnabled(false);
        LocalLastSeen.record(0, 123456, 1, LocalLastSeen.MESSAGE);
        LocalLastSeen.recordRead(0, 123456, 0, 1, 1);
        assertTrue(LocalLastSeen.getHistory(0, 0).isEmpty());
        LocalLastSeen.setEnabled(true);
        for (int i = 1; i <= 502; i++) LocalLastSeen.record(0, 123456, i, LocalLastSeen.MESSAGE);
        assertEquals(500, LocalLastSeen.getHistory(0, 0).size());
        LocalLastSeen.clearHistory(0, 123456);
        assertTrue(LocalLastSeen.getHistory(0, 0).isEmpty());
    }
}
