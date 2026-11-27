package org.telegram.messenger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NotificationAvatarLoadsTest {

    @Test public void aRunningDownloadIsNotStartedTwice() {
        NotificationAvatarLoads loads = new NotificationAvatarLoads();
        assertTrue(loads.begin("1_2.jpg"));
        assertFalse(loads.begin("1_2.jpg"));
        assertTrue(loads.begin("3_4.jpg")); // another photo is independent
    }

    @Test public void aFinishedDownloadRefreshesTheNotificationOnce() {
        NotificationAvatarLoads loads = new NotificationAvatarLoads();
        assertFalse("not ours", loads.finished("1_2.jpg"));
        assertTrue(loads.begin("1_2.jpg"));
        assertTrue(loads.finished("1_2.jpg"));
        assertFalse("already handled", loads.finished("1_2.jpg"));
    }

    @Test public void aFailingDownloadIsRetriedButNotForever() {
        NotificationAvatarLoads loads = new NotificationAvatarLoads();
        for (int i = 0; i < NotificationAvatarLoads.MAX_ATTEMPTS; i++) {
            assertTrue("attempt " + (i + 1), loads.begin("5_6.jpg"));
            loads.failed("5_6.jpg");
        }
        assertFalse("limit reached", loads.begin("5_6.jpg"));
    }

    @Test public void aPhotoThatArrivedMayBeRequestedAgainIfItsFileIsLaterRemoved() {
        NotificationAvatarLoads loads = new NotificationAvatarLoads();
        for (int i = 0; i < 5; i++) {
            assertTrue(loads.begin("7_8.jpg"));
            assertTrue(loads.finished("7_8.jpg"));
        }
    }

    @Test public void emptyNamesAreIgnored() {
        NotificationAvatarLoads loads = new NotificationAvatarLoads();
        assertFalse(loads.begin(null));
        assertFalse(loads.begin(""));
        assertFalse(loads.finished(null));
        loads.failed(null);
    }
}
