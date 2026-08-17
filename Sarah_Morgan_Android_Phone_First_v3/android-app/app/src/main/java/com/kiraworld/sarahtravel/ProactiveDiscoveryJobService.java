package com.kiraworld.sarahtravel;
import android.app.job.JobParameters; import android.app.job.JobService; import java.util.Map;
public final class ProactiveDiscoveryJobService extends JobService {
    @Override public boolean onStartJob(JobParameters p){ new Thread(()->{ SarahDatabase db=new SarahDatabase(getApplicationContext()); PersonProfileStore people=new PersonProfileStore(getApplicationContext());
        try { Map<String,String> owner=db.getProfile(); people.ensureOwner(owner); Map<String,String> active=people.getActiveProfile(); if(active.isEmpty())active=owner;
            active.put("active_speaker_is_owner", active.getOrDefault("is_owner","yes")); ProactiveDiscoveryCoordinator.refresh(getApplicationContext(),active,db.listTrips(20)); }
        catch(Exception ignored){} finally{people.close();db.close();jobFinished(p,false);} },"Sarah-Proactive-Discovery").start(); return true; }
    @Override public boolean onStopJob(JobParameters p){return true;}
}
