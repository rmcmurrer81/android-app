package com.kiraworld.sarahtravel;
import android.app.job.JobInfo; import android.app.job.JobScheduler; import android.content.ComponentName; import android.content.Context; import android.os.PersistableBundle;
public final class ProactiveDiscoveryScheduler {
    private static final int JOB=52201; private ProactiveDiscoveryScheduler(){}
    public static void ensureScheduled(Context c){ JobScheduler js=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE); if(js==null)return;
        JobInfo info=new JobInfo.Builder(JOB,new ComponentName(c,ProactiveDiscoveryJobService.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(12L*60L*60L*1000L).build(); js.schedule(info); }
    public static void runSoon(Context c){ JobScheduler js=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE); if(js==null)return;
        js.schedule(new JobInfo.Builder(JOB+1,new ComponentName(c,ProactiveDiscoveryJobService.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(3000).setOverrideDeadline(30000).build()); }
    public static void cancel(Context c){ JobScheduler js=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE); if(js!=null){js.cancel(JOB);js.cancel(JOB+1);} }
}
