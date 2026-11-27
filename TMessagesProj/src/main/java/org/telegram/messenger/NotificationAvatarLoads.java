package org.telegram.messenger;

import java.util.HashMap;
import java.util.HashSet;

/**
 * Bookkeeping for profile photos that were downloaded only because a notification needed them.
 * A file is requested while no request for it is in flight, and at most {@link #MAX_ATTEMPTS} times,
 * so a photo that can't be downloaded doesn't make the notification refresh itself again and again.
 * Pure Java so it can be unit tested; used from the notifications queue.
 */
final class NotificationAvatarLoads {

    static final int MAX_ATTEMPTS = 2;

    private final HashSet<String> inFlight = new HashSet<>();
    private final HashMap<String, Integer> attempts = new HashMap<>();

    /** True when the caller should start the download now. */
    synchronized boolean begin(String fileName) {
        if (fileName == null || fileName.isEmpty() || inFlight.contains(fileName)) {
            return false;
        }
        final int used = attempts.containsKey(fileName) ? attempts.get(fileName) : 0;
        if (used >= MAX_ATTEMPTS) {
            return false;
        }
        attempts.put(fileName, used + 1);
        inFlight.add(fileName);
        return true;
    }

    /** The download finished: true when it was one of ours, so the notification should be refreshed. */
    synchronized boolean finished(String fileName) {
        if (fileName == null || !inFlight.remove(fileName)) {
            return false;
        }
        attempts.remove(fileName); // it is in the cache now: a later removal of the file may be retried
        return true;
    }

    /** The download failed: it may be retried (up to the attempt limit) by the next notification. */
    synchronized void failed(String fileName) {
        if (fileName != null) {
            inFlight.remove(fileName);
        }
    }
}
