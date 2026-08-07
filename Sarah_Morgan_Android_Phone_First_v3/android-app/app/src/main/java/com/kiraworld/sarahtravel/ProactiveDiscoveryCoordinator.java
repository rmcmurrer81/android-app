package com.kiraworld.sarahtravel;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.List;
import java.util.Map;

public final class ProactiveDiscoveryCoordinator {
    private static final String CHANNEL="sarah_discoveries";
    private ProactiveDiscoveryCoordinator() { }
    public static int refresh(Context context, Map<String,String> profile, List<Map<String,String>> trips) throws Exception {
        SharedPreferences prefs=context.getSharedPreferences(SettingsActivity.PREFS,Context.MODE_PRIVATE);
        if(!prefs.getBoolean("web_search",true)||!prefs.getBoolean("auto_destination_research",true)
                ||SettingsActivity.getConversationMode(context)==ConversationModePolicy.MODE_LOCAL_ONLY
                ||!TavilyClient.configured()) return 0;
        if(!"yes".equals(profile.getOrDefault("active_speaker_is_owner","yes"))
                ||!"yes".equals(profile.getOrDefault("memory_consent","no"))) return 0;
        String speaker=profile.getOrDefault("name","Traveler");
        String interests=profile.getOrDefault("interests",profile.getOrDefault("speaker_memories","travel"));
        String destination=""; if(trips!=null&&!trips.isEmpty()) destination=trips.get(0).getOrDefault("destination","");
        boolean nearby=prefs.getBoolean("nearby_discoveries",false);
        String area=nearby?prefs.getString("nearby_area",profile.getOrDefault("hometown","")):"";
        String query;
        String category;
        if(!destination.isEmpty()) {
            query=interests+" in "+destination+" filming locations museums events official visitor information tickets";
            category="pre_trip";
        } else if(!area.isEmpty()) {
            query=interests+" events appearances signings exhibitions near "+area+" official tickets";
            category="nearby";
        } else return 0;
        if(interests.toLowerCase().contains("power rangers")&&destination.toLowerCase().contains("new zealand")) {
            query="Power Rangers filming locations New Zealand Auckland current visitor information official sources";
        }
        List<TavilyClient.Result> results=TavilyClient.search(query,4);
        ProactiveDiscoveryStore store=new ProactiveDiscoveryStore(context);
        int added=0; try { for(TavilyClient.Result result:results) if(store.add(speaker,result,query,category)) added++; }
        finally { store.close(); }
        if(added>0) notify(context,speaker,added); return added;
    }
    private static void notify(Context context,String speaker,int count) {
        NotificationManager nm=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        if(nm==null)return; if(Build.VERSION.SDK_INT>=26) nm.createNotificationChannel(new NotificationChannel(CHANNEL,"Sarah discoveries",NotificationManager.IMPORTANCE_DEFAULT));
        Intent intent=new Intent(context,DiscoveryActivity.class); PendingIntent pi=PendingIntent.getActivity(context,9901,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification.Builder b=Build.VERSION.SDK_INT>=26?new android.app.Notification.Builder(context,CHANNEL):new android.app.Notification.Builder(context);
        b.setSmallIcon(android.R.drawable.ic_menu_search).setContentTitle("Sarah found something you may like")
                .setContentText(count+" possible match"+(count==1?"":"es")+" for "+speaker+". Tap to review the sources.")
                .setContentIntent(pi).setAutoCancel(true); nm.notify(9901,b.build());
    }
}
