package com.kiraworld.sarahtravel;

import android.content.Context;
import android.net.wifi.WifiManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Owner-visible Android responder for new-Windows pairing and reviewed pull. */
public final class SarahReverseSyncResponder implements AutoCloseable {
    private static final byte[] DISCOVERY_QUERY="SARAH_DISCOVER_V2".getBytes(StandardCharsets.UTF_8);
    private static final int MAX_PAIRING=8192,MAX_SYNC=8_000_000;
    private static final Set<String> OFFER_FIELDS=new HashSet<>(Arrays.asList(
            "schema","kind","request_id","created_at","expires_at","instance_id",
            "device_name","device_type","public_key","nonce","approval_required_on_both_devices"));
    private static final Set<String> SYNC_FIELDS=new HashSet<>(Arrays.asList(
            "schema","kind","device_id","request_id","payload","signature"));
    public interface Listener {
        void onPairingPending(Pending pending);
        void onTrusted(String deviceName);
        void onError(String message);
    }
    public static final class Pending {
        public final String deviceName,sasCode;private Boolean approved;
        Pending(String deviceName,String sasCode){this.deviceName=deviceName;this.sasCode=sasCode;}
        public synchronized void approve(){if(approved!=null)throw new SecurityException("Pairing already decided");approved=true;notifyAll();}
        public synchronized void reject(){if(approved!=null)return;approved=false;notifyAll();}
        synchronized boolean await(long millis)throws InterruptedException{
            long end=System.currentTimeMillis()+millis;
            while(approved==null&&System.currentTimeMillis()<end)wait(Math.max(1,end-System.currentTimeMillis()));
            return Boolean.TRUE.equals(approved);
        }
    }

    private final Context context;private final Listener listener;private final String instanceId;
    private volatile boolean running;private ServerSocket server;private DatagramSocket discovery;
    private WifiManager.MulticastLock multicast;private Thread tcpThread,udpThread;
    public SarahReverseSyncResponder(Context context,Listener listener){
        this.context=context.getApplicationContext();this.listener=listener;
        this.instanceId=SarahPairingProtocol.newInstanceId();
    }
    public synchronized int start()throws Exception{
        if(running)return server.getLocalPort();
        server=new ServerSocket(0);server.setReuseAddress(true);server.setSoTimeout(500);
        discovery=new DatagramSocket(SarahDeviceDiscovery.DISCOVERY_PORT,InetAddress.getByName("0.0.0.0"));
        discovery.setBroadcast(true);discovery.setSoTimeout(500);
        WifiManager wifi=(WifiManager)context.getSystemService(Context.WIFI_SERVICE);
        if(wifi!=null){multicast=wifi.createMulticastLock("SarahReverseResponder");multicast.setReferenceCounted(false);multicast.acquire();}
        running=true;
        tcpThread=new Thread(this::tcpLoop,"Sarah-Android-Pairing-Responder");tcpThread.start();
        udpThread=new Thread(this::udpLoop,"Sarah-Android-Discovery-Responder");udpThread.start();
        return server.getLocalPort();
    }
    public boolean isRunning(){return running&&server!=null&&!server.isClosed();}

    private void udpLoop(){
        byte[] buffer=new byte[2049];
        while(running)try{
            DatagramPacket packet=new DatagramPacket(buffer,buffer.length);discovery.receive(packet);
            byte[] received=Arrays.copyOfRange(packet.getData(),packet.getOffset(),packet.getOffset()+packet.getLength());
            if(!MessageDigest.isEqual(DISCOVERY_QUERY,received))continue;
            JSONObject value=new JSONObject();long now=System.currentTimeMillis()/1000L;
            value.put("schema",SarahDeviceDiscovery.DISCOVERY_SCHEMA);value.put("instance_id",instanceId);
            value.put("device_name",android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL+" Sarah");
            value.put("device_type","android-phone");value.put("pairing_protocol",SarahPairingProtocol.SCHEMA);
            value.put("pairing_port",server.getLocalPort());value.put("expires_at",now+15L);
            value.put("approval_required_on_both_devices",true);
            byte[] reply=value.toString().getBytes(StandardCharsets.UTF_8);
            discovery.send(new DatagramPacket(reply,reply.length,packet.getAddress(),packet.getPort()));
        }catch(SocketTimeoutException ignored){}catch(Exception error){if(running)listener.onError("Android discovery stopped: "+safe(error));}
    }

    private void tcpLoop(){while(running)try{Socket socket=server.accept();new Thread(()->handle(socket),"Sarah-Android-Secure-Peer").start();}
        catch(SocketTimeoutException ignored){}catch(Exception error){if(running)listener.onError("Android device responder stopped: "+safe(error));}}

    private void handle(Socket socket){try(Socket peer=socket){
        String host=TrustedLanEndpointPolicy.requireLocalHost(peer.getInetAddress().getHostAddress());
        peer.setSoTimeout(125_000);DataInputStream input=new DataInputStream(peer.getInputStream());DataOutputStream output=new DataOutputStream(peer.getOutputStream());
        JSONObject first=readJson(input,MAX_PAIRING);
        if(SarahPairingProtocol.SCHEMA.equals(first.optString("schema")))handlePairing(host,first,input,output);
        else handlePreview(host,first,output);
    }catch(Exception error){listener.onError("Secure device request failed: "+safe(error));}}

    private void handlePairing(String host,JSONObject offer,DataInputStream input,DataOutputStream output)throws Exception{
        requireFields(offer,OFFER_FIELDS,"pairing offer");
        SarahPairingProtocol.Response response=SarahPairingProtocol.respond(
                map(offer),instanceId,android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL+" Sarah","android-phone",now());
        writeJson(output,new JSONObject(response.message),MAX_PAIRING);
        Pending pending=new Pending(offer.optString("device_name","Windows Sarah"),response.session.sasCode);
        listener.onPairingPending(pending);
        if(!pending.await(121_000L))throw new SecurityException("Android owner rejected or did not confirm pairing");
        JSONObject peerConfirmation=readJson(input,MAX_PAIRING);
        response.session.acceptPeerConfirmation(map(peerConfirmation),now());
        writeJson(output,new JSONObject(response.session.localConfirmation(true,now())),MAX_PAIRING);
        SarahPairingProtocol.Credential credential=response.session.finalizeCredential(now());
        if(!TrustedDeviceStore.saveFinalizedPeer(context,host,server.getLocalPort(),credential))
            throw new SecurityException("Android Keystore did not save finalized Windows trust");
        listener.onTrusted(credential.peerDeviceName);
    }

    private void handlePreview(String host,JSONObject request,DataOutputStream output)throws Exception{
        requireFields(request,SYNC_FIELDS,"secure sync request");
        if(!SecureSyncPreviewClient.SCHEMA.equals(request.optString("schema"))
                ||!"preview_request".equals(request.optString("kind")))throw new SecurityException("Unsupported secure sync operation");
        String deviceId=request.optString("device_id","").trim();String requestId=request.optString("request_id","").trim();
        String trustedHost=TrustedDeviceStore.hostForPeerInstanceId(context,deviceId);
        if(trustedHost.isEmpty()||!trustedHost.equalsIgnoreCase(host))throw new SecurityException("Windows device is not paired or host binding changed");
        String token=TrustedDeviceStore.tokenFor(context,trustedHost);if(token.isEmpty())throw new SecurityException("Windows device trust was revoked");
        String encrypted=request.optString("payload","");
        byte[] expected=TrustedSyncProtocol.signature(token,encrypted).getBytes(StandardCharsets.UTF_8);
        byte[] actual=request.optString("signature","").getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(expected,actual))throw new SecurityException("Secure sync authentication failed");
        JSONObject inside=new JSONObject(TrustedSyncProtocol.decrypt(token,encrypted));
        if(!"preview_request".equals(inside.optString("kind"))||!deviceId.equals(inside.optString("device_id"))||!requestId.equals(inside.optString("request_id")))
            throw new SecurityException("Encrypted secure sync identity binding failed");
        SyncSeenStore seen=new SyncSeenStore(context);try{if(!seen.first("secure-sync-request:"+deviceId+":"+requestId))throw new SecurityException("Secure sync replay rejected");}finally{seen.close();}
        JSONObject payload=SarahSyncExporter.exportOwnerReview(context);JSONObject counts=new JSONObject();
        for(String key:new String[]{"messages","memories","trips","wishes"}){JSONArray rows=payload.optJSONArray(key);counts.put(key,rows==null?0:rows.length());}
        JSONObject plain=new JSONObject();plain.put("kind","preview_response");plain.put("request_id",requestId);
        plain.put("transfer_id",java.util.UUID.randomUUID().toString());plain.put("source_name",payload.optJSONObject("profile").optString("name",""));
        plain.put("counts",counts);plain.put("payload",payload);plain.put("owner_import_required",true);
        plain.put("merge_policy","APPEND_NEW_KEEP_EXISTING_RECORD_CONFLICTS");plain.put("transfer_direction","ANDROID_TO_WINDOWS_PULL_ONLY");
        String responseEncrypted=TrustedSyncProtocol.encrypt(token,plain.toString());JSONObject response=new JSONObject();
        response.put("schema",SecureSyncPreviewClient.SCHEMA);response.put("kind","preview_response");response.put("device_id",deviceId);response.put("request_id",requestId);
        response.put("payload",responseEncrypted);response.put("signature",TrustedSyncProtocol.signature(token,responseEncrypted));writeJson(output,response,MAX_SYNC);
    }

    private static JSONObject readJson(DataInputStream input,int maximum)throws Exception{int length=input.readInt();if(length<1||length>maximum)throw new SecurityException("Device frame empty or oversized");byte[] bytes=new byte[length];input.readFully(bytes);return new JSONObject(new String(bytes,StandardCharsets.UTF_8));}
    private static void writeJson(DataOutputStream output,JSONObject value,int maximum)throws Exception{byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length<1||bytes.length>maximum)throw new SecurityException("Device frame empty or oversized");output.writeInt(bytes.length);output.write(bytes);output.flush();}
    private static Map<String,Object> map(JSONObject value)throws Exception{Map<String,Object> result=new LinkedHashMap<>();Iterator<String> keys=value.keys();while(keys.hasNext()){String key=keys.next();result.put(key,value.get(key));}return result;}
    private static void requireFields(JSONObject value,Set<String> expected,String kind){Set<String> actual=new HashSet<>();Iterator<String> keys=value.keys();while(keys.hasNext())actual.add(keys.next());if(!actual.equals(expected))throw new SecurityException(kind+" contains unexpected or missing fields");}
    private static long now(){return System.currentTimeMillis()/1000L;}private static String safe(Exception error){String value=error.getMessage();return value==null?error.getClass().getSimpleName():value;}
    @Override public synchronized void close(){running=false;try{if(server!=null)server.close();}catch(Exception ignored){}try{if(discovery!=null)discovery.close();}catch(Exception ignored){}if(multicast!=null&&multicast.isHeld())multicast.release();server=null;discovery=null;}
}
