package com.kiraworld.sarahtravel;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

public final class DealWatchScheduler {
    private static final int JOB_PERIODIC = 4601;
    private static final int JOB_SOON = 4602;
    private static final long TWELVE_HOURS_MS = 12L * 60L * 60L * 1000L;

    private DealWatchScheduler() { }

    public static boolean ensureScheduled(Context context) {
        Context app = context.getApplicationContext();
        JobScheduler scheduler = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return false;
        JobInfo job = new JobInfo.Builder(
                JOB_PERIODIC,
                new ComponentName(app, DealWatchWorker.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(TWELVE_HOURS_MS)
                .build();
        return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS;
    }

    public static boolean runSoon(Context context) {
        Context app = context.getApplicationContext();
        JobScheduler scheduler = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return false;
        JobInfo job = new JobInfo.Builder(
                JOB_SOON,
                new ComponentName(app, DealWatchWorker.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(1000L)
                .setOverrideDeadline(60L * 1000L)
                .setBackoffCriteria(5L * 60L * 1000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build();
        return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS;
    }

    public static void cancel(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        scheduler.cancel(JOB_PERIODIC);
        scheduler.cancel(JOB_SOON);
    }
}
