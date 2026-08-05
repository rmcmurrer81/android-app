package com.kiraworld.sarahtravel;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

public final class EventMonitorScheduler {
    private static final int PERIODIC_JOB_ID = 74120;
    private static final int IMMEDIATE_JOB_ID = 74121;
    private static final long SIX_HOURS_MS = 6L * 60L * 60L * 1000L;

    private EventMonitorScheduler() { }

    public static void ensureScheduled(Context context) {
        Context app = context.getApplicationContext();
        JobScheduler scheduler = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        JobInfo job = new JobInfo.Builder(
                PERIODIC_JOB_ID,
                new ComponentName(app, EventMonitorJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(SIX_HOURS_MS)
                .build();
        scheduler.schedule(job);
    }

    public static void runSoon(Context context) {
        Context app = context.getApplicationContext();
        JobScheduler scheduler = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        JobInfo job = new JobInfo.Builder(
                IMMEDIATE_JOB_ID,
                new ComponentName(app, EventMonitorJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(1_000L)
                .setOverrideDeadline(20_000L)
                .build();
        scheduler.schedule(job);
    }
}
