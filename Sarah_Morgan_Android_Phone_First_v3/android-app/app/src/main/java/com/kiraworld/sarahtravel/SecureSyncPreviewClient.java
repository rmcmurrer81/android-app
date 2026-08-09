package com.kiraworld.sarahtravel;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Encrypted post-pairing pull. Nothing is imported until the owner accepts Preview. */
public final class SecureSyncPreviewClient {
    public static final String SCHEMA="sarah-secure-sync-v2";
    private static final int MAX_BYTES=8_000_000;
    private SecureSyncPreviewClient(){}

    public static final class Preview {
        public final String host;
        public final String sourceName;
        public final String transferId;
        public final JSONObject counts;
        private final JSONObject payload;
        private final AtomicBoolean applied=new AtomicBoolean(false);
        Preview(String host,String sourceName,String transferId,JSONObject counts,JSONObject payload){
            this.host=host;this.sourceName=sourceName;this.transferId=transferId;
            this.counts=counts;this.payload=payload;
        }
        public int apply(Context context)throws Exception{
            if(!applied.compareAndSet(false,true))throw new IllegalStateException("This preview was already decided.");
            SyncImportProvenance.recordDecision(context,host,transferId,payload,counts);
            int imported=SarahSyncImporter.importPayload(context,payload);
            SyncImportProvenance.recordResult(context,host,transferId,payload,counts,imported);
            return imported;
        }
        public String summary(){
            return "Profile: "+(sourceName.isEmpty()?"confirmed owner":sourceName)
                    +"\nConversation messages: "+counts.optInt("messages",0)
                    +"\nApproved memories: "+counts.optInt("memories",0)
                    +"\nTrips: "+counts.optInt("trips",0)
                    +"\nTravel wishes: "+counts.optInt("wishes",0)
                    +"\n\nImport adds new records and keeps existing records. Photos, Gmail access, provider/model/voice secrets, private-mind records, and other people's data are excluded.";
        }
    }

    public static Preview fetch(Context context,String rawHost)throws Exception{
        String host=TrustedLanEndpointPolicy.requireLocalHost(rawHost);
        int port=TrustedDeviceStore.portFor(context,host);
        String token=TrustedDeviceStore.tokenFor(context,host);
        if(port<1||token.isEmpty())throw new SecurityException("Pair and approve this exact Sarah device first.");
        String deviceId=TrustedDeviceStore.localDeviceId(context);
        String requestId=UUID.randomUUID().toString();
        JSONObject inside=new JSONObject();
        inside.put("kind","preview_request");inside.put("device_id",deviceId);inside.put("request_id",requestId);
        String encrypted=TrustedSyncProtocol.encrypt(token,inside.toString());
        JSONObject request=new JSONObject();
        request.put("schema",SCHEMA);request.put("kind","preview_request");
        request.put("device_id",deviceId);request.put("request_id",requestId);
        request.put("payload",encrypted);request.put("signature",TrustedSyncProtocol.signature(token,encrypted));

        JSONObject response;
        try(Socket socket=new Socket()){
            socket.connect(new InetSocketAddress(host,port),10_000);
            socket.setSoTimeout(30_000);
            writeFrame(socket,request);
            response=readFrame(socket);
        }
        if(!SCHEMA.equals(response.optString("schema"))
                ||!"preview_response".equals(response.optString("kind"))
                ||!deviceId.equals(response.optString("device_id"))
                ||!requestId.equals(response.optString("request_id")))
            throw new SecurityException("The sync preview response was not bound to this request.");
        String incoming=response.optString("payload","");
        byte[] actual=response.optString("signature","").getBytes(StandardCharsets.UTF_8);
        byte[] expected=TrustedSyncProtocol.signature(token,incoming).getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(expected,actual))throw new SecurityException("The sync preview signature failed.");
        JSONObject plain=new JSONObject(TrustedSyncProtocol.decrypt(token,incoming));
        if(!"preview_response".equals(plain.optString("kind"))
                ||!requestId.equals(plain.optString("request_id"))
                ||!plain.optBoolean("owner_import_required",false)
                ||!"WINDOWS_TO_ANDROID_PULL_ONLY".equals(plain.optString("transfer_direction")))
            throw new SecurityException("The encrypted sync preview binding failed.");
        JSONObject payload=plain.optJSONObject("payload");
        if(payload==null||!"sarah-sync-v1".equals(payload.optString("schema")))
            throw new SecurityException("The sync preview contains no supported Sarah continuity package.");
        rejectNonOwnerData(payload);
        return new Preview(host,plain.optString("source_name",""),plain.optString("transfer_id",""),
                plain.optJSONObject("counts")==null?new JSONObject():plain.optJSONObject("counts"),payload);
    }

    private static void rejectNonOwnerData(JSONObject payload)throws Exception{
        for(String key:new String[]{"photos","mind_events","discoveries"}){
            JSONArray rows=payload.optJSONArray(key);
            if(rows!=null&&rows.length()>0)throw new SecurityException("The preview crossed the approved data boundary: "+key);
        }
        JSONObject boundary=payload.optJSONObject("transfer_boundary");
        if(boundary==null)throw new SecurityException("The preview omitted its transfer-boundary receipt.");
    }

    private static void writeFrame(Socket socket,JSONObject value)throws Exception{
        byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length<1||bytes.length>MAX_BYTES)throw new IllegalStateException("Secure sync request is empty or oversized.");
        DataOutputStream output=new DataOutputStream(socket.getOutputStream());
        output.writeInt(bytes.length);output.write(bytes);output.flush();
    }
    private static JSONObject readFrame(Socket socket)throws Exception{
        DataInputStream input=new DataInputStream(socket.getInputStream());
        int length=input.readInt();
        if(length<1||length>MAX_BYTES)throw new IllegalStateException("Secure sync response is empty or oversized.");
        byte[] bytes=new byte[length];input.readFully(bytes);
        return new JSONObject(new String(bytes,StandardCharsets.UTF_8));
    }
}
