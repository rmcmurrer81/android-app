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

    /** The retired plaintext-HTTP path may never persist an unverified token. */
    @Deprecated
    public static synchronized void savePeer(Context c,String host,String token){
        throw new SecurityException(
                "Only a finalized two-device X25519 pairing credential may be stored.");
    }

    public static synchronized boolean saveFinalizedPeer(
            Context c,
            String host,
            int port,
            SarahPairingProtocol.Credential credential){
        if(c==null||credential==null)return false;
        try { host=TrustedLanEndpointPolicy.requireLocalHost(host); }
        catch (RuntimeException rejected) { return false; }
        if(port<1||port>65535)return false;
        String token=clean(credential.token);
        if(host.isEmpty()||!token.matches("[A-Za-z0-9_-]{43}"))return false;
        if(clean(credential.requestId).isEmpty()
                ||clean(credential.peerInstanceId).isEmpty()
                ||clean(credential.peerDeviceName).isEmpty()
                ||clean(credential.peerDeviceType).isEmpty())return false;
        if(!SecureProfileVault.putVerified(c,TOKEN_NAMESPACE,hostKey(host),token))return false;
        JSONObject peers=read(c); JSONObject peer=new JSONObject();
        try{
            peer.put("host",host);
            peer.put("secure_sync_port",port);
            peer.put("paired_at",credential.establishedAt*1000L);
            peer.put("peer_instance_id",credential.peerInstanceId);
            peer.put("peer_device_name",credential.peerDeviceName);
            peer.put("peer_device_type",credential.peerDeviceType);
            peer.put("pairing_request_id",credential.requestId);
            peer.put("pairing_protocol",SarahPairingProtocol.SCHEMA);
            peer.put("token_storage","ANDROID_KEYSTORE");
            peers.put(host,peer);
        }catch(Exception ignored){
            SecureProfileVault.removeVerified(c,TOKEN_NAMESPACE,hostKey(host));
            return false;
        }
        boolean metadataCommitted=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                .edit().putString(PEERS,peers.toString()).putString(SELECTED,host).commit();
        if(!metadataCommitted){
            SecureProfileVault.removeVerified(c,TOKEN_NAMESPACE,hostKey(host));
            return false;
        }
        JSONObject verified=read(c).optJSONObject(host);
        return verified!=null
                &&SarahPairingProtocol.SCHEMA.equals(verified.optString("pairing_protocol",""))
                &&token.equals(SecureProfileVault.get(c,TOKEN_NAMESPACE,hostKey(host)));
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
            for(String host:keys){JSONObject peer=peers.optJSONObject(host);if(peer==null)continue;String legacy=clean(peer.optString("token",""));
                if(!legacy.isEmpty()){
                    // A token returned by the retired plaintext-HTTP pairing
                    // prototype is not a finalized SAS-bound credential.
                    peers.remove(host);changed=true;continue;
                }
                if(!SarahPairingProtocol.SCHEMA.equals(peer.optString("pairing_protocol",""))){
                    SecureProfileVault.removeVerified(c,TOKEN_NAMESPACE,hostKey(host));
                    peers.remove(host);changed=true;
                }
            }
            if(changed)write(c,peers);
            return peers;
        }
        catch(Exception ignored){return new JSONObject();}
    }

    public static synchronized int portFor(Context c,String host){
        try { host=TrustedLanEndpointPolicy.requireLocalHost(host); }
        catch (RuntimeException rejected) { return 0; }
        JSONObject peer=read(c).optJSONObject(host);
        if(peer==null)return 0;
        int port=peer.optInt("secure_sync_port",0);
        return port>0&&port<=65535?port:0;
    }

    public static synchronized String peerInstanceIdFor(Context c,String host){
        try { host=TrustedLanEndpointPolicy.requireLocalHost(host); }
        catch (RuntimeException rejected) { return ""; }
        JSONObject peer=read(c).optJSONObject(host);
        return peer==null?"":clean(peer.optString("peer_instance_id",""));
    }

    public static synchronized String hostForPeerInstanceId(Context c,String peerInstanceId){
        String wanted=clean(peerInstanceId);if(wanted.isEmpty())return "";
        JSONObject peers=read(c);Iterator<String> keys=peers.keys();
        while(keys.hasNext()){
            String host=keys.next();JSONObject peer=peers.optJSONObject(host);
            if(peer!=null&&wanted.equals(clean(peer.optString("peer_instance_id",""))))return host;
        }
        return "";
    }
    private static void write(Context c,JSONObject value){c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(PEERS,value.toString()).apply();}
    private static String hostKey(String host){return UUID.nameUUIDFromBytes(clean(host).toLowerCase().getBytes(StandardCharsets.UTF_8)).toString();}
    private static String clean(String v){return v==null?"":v.trim();}
}
