package com.kiraworld.sarahtravel;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;

import java.util.concurrent.atomic.AtomicLong;

public final class ProactiveDiscoveryScheduler {
    private static final int PERIODIC_JOB_ID = 52201;
    private static final int IMMEDIATE_JOB_ID = 52202;
    static final String EXTRA_SCHEDULE_TOKEN = "sarah_proactive_schedule_token";
    private static final AtomicLong NEXT_TOKEN =
            new AtomicLong(System.currentTimeMillis());

    private ProactiveDiscoveryScheduler() { }

    public static boolean ensureScheduled(Context context) {
        Context app = context.getApplicationContext();
        JobScheduler scheduler = (JobScheduler) app.getSystemService(
                Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return false;
        JobInfo pending = scheduler.getPendingJob(PERIODIC_JOB_ID);
        if (pending != null && pending.getExtras().getLong(
                EXTRA_SCHEDULE_TOKEN, 0L) != 0L) return true;
        if (pending != null) scheduler.cancel(PERIODIC_JOB_ID);
        PersistableBundle extras = new PersistableBundle();
        extras.putLong(EXTRA_SCHEDULE_TOKEN, NEXT_TOKEN.incrementAndGet());
        JobInfo info = new JobInfo.Builder(
                PERIODIC_JOB_ID,
                new ComponentName(app, ProactiveDiscoveryJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setExtras(extras)
                .setPersisted(true)
                .setPeriodic(12L * 60L * 60L * 1000L)
                .build();
        return scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS;
    }

    public static boolean runSoon(Context context) {
        Context app = context.getApplicationContext();
        JobScheduler scheduler = (JobScheduler) app.getSystemService(
                Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return false;
        PersistableBundle extras = new PersistableBundle();
        extras.putLong(EXTRA_SCHEDULE_TOKEN, NEXT_TOKEN.incrementAndGet());
        JobInfo info = new JobInfo.Builder(
                IMMEDIATE_JOB_ID,
                new ComponentName(app, ProactiveDiscoveryJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setExtras(extras)
                .setMinimumLatency(3_000L)
                .setOverrideDeadline(30_000L)
                .build();
        return scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS;
    }

    public static void cancel(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getApplicationContext()
                .getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        scheduler.cancel(PERIODIC_JOB_ID);
        scheduler.cancel(IMMEDIATE_JOB_ID);
    }
}
