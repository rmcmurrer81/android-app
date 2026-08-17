package com.kiraworld.sarahtravel;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class TrustedSyncClient {
    private TrustedSyncClient(){}

    public static String pair(Context c,String host,String code)throws Exception{
        host=host==null?"":host.trim();JSONObject b=new JSONObject();b.put("device_id",TrustedDeviceStore.localDeviceId(c));
        b.put("device_name",android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL);b.put("code",code);
        JSONObject r=post("http://"+host+":8769/pair",b.toString(),"");String token=r.getString("token");
        TrustedDeviceStore.savePeer(c,host,token);return token;
    }

    public static JSONObject sync(Context c)throws Exception{return syncHost(c,TrustedDeviceStore.host(c));}

    public static JSONObject syncHost(Context c,String host)throws Exception{
        String token=TrustedDeviceStore.tokenFor(c,host);if(host==null||host.isEmpty()||token.isEmpty())throw new IllegalStateException("Pair with this Sarah computer first.");
        String encrypted=TrustedSyncProtocol.encrypt(token,SarahSyncExporter.export(c).toString());JSONObject body=new JSONObject();
        body.put("payload",encrypted);body.put("signature",TrustedSyncProtocol.signature(token,encrypted));
        JSONObject response=post("http://"+host+":8769/sync",body.toString(),token);String incoming=response.optString("payload","");
        if(!incoming.isEmpty()){String signature=response.optString("signature","");if(!TrustedSyncProtocol.signature(token,incoming).equals(signature))throw new SecurityException("Computer reply signature failed");
            int imported=SarahSyncImporter.importPayload(c,new JSONObject(TrustedSyncProtocol.decrypt(token,incoming)));
            response.put("message",response.optString("message","Sync completed.")+" Android imported "+imported+" new item(s).");}
        return response;
    }

    public static JSONObject syncAll(Context c){
        JSONArray successes=new JSONArray(),failures=new JSONArray();
        for(String host:TrustedDeviceStore.hosts(c)){try{JSONObject result=syncHost(c,host);JSONObject row=new JSONObject();row.put("host",host);row.put("message",result.optString("message","Synced"));successes.put(row);}catch(Exception e){JSONObject row=new JSONObject();try{row.put("host",host);row.put("error",e.getMessage());}catch(Exception ignored){}failures.put(row);}}
        JSONObject result=new JSONObject();try{result.put("successes",successes);result.put("failures",failures);result.put("message","Synced "+successes.length()+" computer(s); "+failures.length()+" unavailable.");}catch(Exception ignored){}return result;
    }

    public static void syncAllAsync(Context c){
        if(!TrustedDeviceStore.hasPeers(c)||!c.getSharedPreferences(SettingsActivity.PREFS,Context.MODE_PRIVATE).getBoolean("auto_device_sync",true))return;
        Context app=c.getApplicationContext();new Thread(()->syncAll(app),"Sarah-Multi-Device-Sync").start();
    }

    private static JSONObject post(String endpoint,String body,String token)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(endpoint).openConnection();c.setConnectTimeout(10000);c.setReadTimeout(60000);c.setRequestMethod("POST");c.setDoOutput(true);
        c.setRequestProperty("Content-Type","application/json");if(!token.isEmpty())c.setRequestProperty("X-Sarah-Device-Token",token);
        try(OutputStream o=c.getOutputStream()){o.write(body.getBytes(StandardCharsets.UTF_8));}int status=c.getResponseCode();InputStream s=status>=200&&status<300?c.getInputStream():c.getErrorStream();
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();if(s!=null)try(InputStream in=s){byte[] b=new byte[8192];int n;while((n=in.read(b))>=0)bytes.write(b,0,n);}c.disconnect();
        if(status<200||status>=300)throw new IllegalStateException("Sarah computer returned "+status+": "+bytes.toString(StandardCharsets.UTF_8));return new JSONObject(bytes.toString(StandardCharsets.UTF_8));
    }
}
