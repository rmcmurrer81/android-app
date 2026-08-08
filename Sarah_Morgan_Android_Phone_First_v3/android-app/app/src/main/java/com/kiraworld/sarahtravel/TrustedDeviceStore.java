package com.kiraworld.sarahtravel;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/** Stores several explicitly paired Sarah computers; Wi-Fi alone never grants trust. */
public final class TrustedDeviceStore {
    private static final String PREFS="sarah_trusted_devices";
    private static final String PEERS="peers_json";
    private static final String SELECTED="selected_host";
    private static final String TOKEN_NAMESPACE="trusted_peer_token_v2";
    private TrustedDeviceStore(){}

    public static String localDeviceId(Context c){
        SharedPreferences p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        String id=p.getString("local_device_id","");
        if(id.isEmpty()){id=UUID.randomUUID().toString();p.edit().putString("local_device_id",id).apply();}
        return id;
    }

    public static synchronized void savePeer(Context c,String host,String token){
        try { host=TrustedLanEndpointPolicy.requireLocalHost(host); }
        catch (RuntimeException rejected) { return; }
        token=clean(token); if(host.isEmpty()||token.isEmpty())return;
        if(!SecureProfileVault.putVerified(c,TOKEN_NAMESPACE,hostKey(host),token))return;
        JSONObject peers=read(c); JSONObject peer=new JSONObject();
        try{peer.put("host",host);peer.put("paired_at",System.currentTimeMillis());peer.put("token_storage","ANDROID_KEYSTORE");peers.put(host,peer);}catch(Exception ignored){}
        write(c,peers);c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(SELECTED,host).apply();
    }

    public static synchronized List<String> hosts(Context c){
        List<String> result=new ArrayList<>();Iterator<String> keys=read(c).keys();while(keys.hasNext())result.add(keys.next());return result;
    }

    public static synchronized String tokenFor(Context c,String host){
        String exactHost;
        try { exactHost=TrustedLanEndpointPolicy.requireLocalHost(host); }
        catch (RuntimeException rejected) { return ""; }
        JSONObject peers=read(c);
        for(String candidate:new String[]{exactHost,exactHost+":"+TrustedLanEndpointPolicy.PORT}){
            if(peers.optJSONObject(candidate)==null)continue;
            String token=SecureProfileVault.get(c,TOKEN_NAMESPACE,hostKey(candidate));
            if(!token.isEmpty())return token;
        }
        return "";
    }

    public static synchronized void select(Context c,String host){
        if(read(c).has(clean(host)))c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(SELECTED,clean(host)).apply();
    }

    public static String host(Context c){
        String selected=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(SELECTED,"");
        if(!selected.isEmpty()&&read(c).has(selected))return selected;
        List<String> all=hosts(c);return all.isEmpty()?"":all.get(0);
    }
    public static String token(Context c){return tokenFor(c,host(c));}
    public static boolean hasPeers(Context c){return !hosts(c).isEmpty();}

    public static synchronized void revoke(Context c,String host){
        String exactHost=clean(host);JSONObject peers=read(c);peers.remove(exactHost);write(c,peers);
        SecureProfileVault.removeVerified(c,TOKEN_NAMESPACE,hostKey(exactHost));
        SharedPreferences p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        if(exactHost.equals(p.getString(SELECTED,"")))p.edit().remove(SELECTED).apply();
    }
    public static void revoke(Context c){revoke(c,host(c));}

    private static JSONObject read(Context c){
        try{
            JSONObject peers=new JSONObject(c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(PEERS,"{}"));
            List<String> keys=new ArrayList<>();Iterator<String> iterator=peers.keys();while(iterator.hasNext())keys.add(iterator.next());
            boolean changed=false;
            for(String host:keys){JSONObject peer=peers.optJSONObject(host);if(peer==null)continue;String legacy=clean(peer.optString("token",""));if(legacy.isEmpty())continue;
                boolean secured=SecureProfileVault.putVerified(c,TOKEN_NAMESPACE,hostKey(host),legacy);
                peer.remove("token");changed=true;
                if(secured){try{peer.put("token_storage","ANDROID_KEYSTORE");}catch(Exception ignored){}}
                else peers.remove(host);
            }
            if(changed)write(c,peers);
            return peers;
        }
        catch(Exception ignored){return new JSONObject();}
    }
    private static void write(Context c,JSONObject value){c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(PEERS,value.toString()).apply();}
    private static String hostKey(String host){return UUID.nameUUIDFromBytes(clean(host).toLowerCase().getBytes(StandardCharsets.UTF_8)).toString();}
    private static String clean(String v){return v==null?"":v.trim();}
}
