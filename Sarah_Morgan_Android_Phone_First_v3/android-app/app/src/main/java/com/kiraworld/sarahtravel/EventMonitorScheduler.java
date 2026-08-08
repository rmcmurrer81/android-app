package com.kiraworld.sarahtravel;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;

import java.util.concurrent.atomic.AtomicLong;

public final class EventMonitorScheduler {
    private static final int PERIODIC_JOB_ID = 74120;
    private static final int IMMEDIATE_JOB_ID = 74121;
    private static final long SIX_HOURS_MS = 6L * 60L * 60L * 1000L;
    static final String EXTRA_SCHEDULE_TOKEN = "sarah_event_schedule_token";
    private static final AtomicLong NEXT_TOKEN = new AtomicLong(System.currentTimeMillis());

    private EventMonitorScheduler() { }

    public static boolean ensureScheduled(Context context) {
        Context app = context.getApplicationContext();
        JobScheduler scheduler = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return false;
        JobInfo pending = scheduler.getPendingJob(PERIODIC_JOB_ID);
        if (pending != null && pending.getExtras().getLong(
                EXTRA_SCHEDULE_TOKEN, 0L) != 0L) return true;
        if (pending != null) scheduler.cancel(PERIODIC_JOB_ID);
        PersistableBundle extras = new PersistableBundle();
        extras.putLong(EXTRA_SCHEDULE_TOKEN, NEXT_TOKEN.incrementAndGet());
        JobInfo job = new JobInfo.Builder(
                PERIODIC_JOB_ID,
                new ComponentName(app, EventMonitorJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setExtras(extras)
                .setPersisted(true)
                .setPeriodic(SIX_HOURS_MS)
                .build();
        return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS;
    }

    public static boolean runSoon(Context context) {
        Context app = context.getApplicationContext();
        JobScheduler scheduler = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return false;
        PersistableBundle extras = new PersistableBundle();
        extras.putLong(EXTRA_SCHEDULE_TOKEN, NEXT_TOKEN.incrementAndGet());
        JobInfo job = new JobInfo.Builder(
                IMMEDIATE_JOB_ID,
                new ComponentName(app, EventMonitorJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setExtras(extras)
                .setMinimumLatency(1_000L)
                .setOverrideDeadline(20_000L)
                .build();
        return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS;
    }

    public static boolean isScheduled(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getApplicationContext()
                .getSystemService(Context.JOB_SCHEDULER_SERVICE);
        return scheduler != null
                && (scheduler.getPendingJob(PERIODIC_JOB_ID) != null
                        || scheduler.getPendingJob(IMMEDIATE_JOB_ID) != null);
    }

    public static boolean isDurablyScheduled(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getApplicationContext()
                .getSystemService(Context.JOB_SCHEDULER_SERVICE);
        return scheduler != null && scheduler.getPendingJob(PERIODIC_JOB_ID) != null;
    }

    public static void cancel(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getApplicationContext()
                .getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        scheduler.cancel(PERIODIC_JOB_ID);
        scheduler.cancel(IMMEDIATE_JOB_ID);
    }

    /** Stop only durable monitoring; preserve an exact queued booking refresh. */
    public static void cancelPeriodicMonitoring(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getApplicationContext()
                .getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null) scheduler.cancel(PERIODIC_JOB_ID);
    }
}
