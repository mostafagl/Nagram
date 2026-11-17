package org.telegram.utils.proxy;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Periodic background update of V2Ray subscriptions, scheduled by {@link V2RaySubscriptions#scheduleJob()}. */
public class V2RaySubscriptionJob extends JobService {

    /** JobScheduler gives a job about ten minutes; leave room to save the results. */
    private static final long TIME_BUDGET_MS = 8 * 60_000L;

    private volatile boolean stopped;

    @Override
    public boolean onStartJob(JobParameters params) {
        stopped = false;
        new Thread(() -> {
            try {
                V2RaySubscriptions.updateDue(new V2RaySubscriptions.Progress() {
                    @Override
                    public void onProgress(int tested, int total, int working) {
                    }

                    @Override
                    public boolean isCancelled() {
                        return stopped;
                    }
                }, TIME_BUDGET_MS);
            } finally {
                jobFinished(params, false);
            }
        }, "v2ray-subscriptions").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // Stopped by the system (constraints or time): stop testing; whatever was found so far is
        // kept, and the next period continues.
        stopped = true;
        return false;
    }
}
