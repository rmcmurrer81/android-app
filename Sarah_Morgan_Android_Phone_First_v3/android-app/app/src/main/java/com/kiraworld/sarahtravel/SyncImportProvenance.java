package com.kiraworld.sarahtravel;

import android.content.Context;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Append-only receipt for each owner-approved continuity import; stores no content. */
public final class SyncImportProvenance {
    private SyncImportProvenance(){}
    public static synchronized void recordDecision(Context context,String host,String transferId,
            JSONObject payload,JSONObject counts)throws Exception{
        append(context,"OWNER_APPROVED_SECURE_SYNC_IMPORT",host,transferId,payload,counts,-1);
    }
    public static synchronized void recordResult(Context context,String host,String transferId,
            JSONObject payload,JSONObject counts,int imported)throws Exception{
        append(context,"SECURE_SYNC_IMPORT_COMPLETED",host,transferId,payload,counts,imported);
    }
    private static void append(Context context,String event,String host,String transferId,
            JSONObject payload,JSONObject counts,int imported)throws Exception{
        JSONObject row=new JSONObject();
        row.put("event",event);
        row.put("recorded_at",System.currentTimeMillis());
        row.put("host",host);row.put("transfer_id",transferId);
        row.put("source_device_id",payload.optString("device_id","unknown"));
        row.put("package_sha256",hex(MessageDigest.getInstance("SHA-256")
                .digest(payload.toString().getBytes(StandardCharsets.UTF_8))));
        row.put("offered_counts",counts);
        if(imported>=0){
            row.put("new_rows_imported",imported);
            int offered=counts.optInt("messages",0)+counts.optInt("memories",0)
                    +counts.optInt("trips",0)+counts.optInt("wishes",0);
            row.put("not_added_existing_or_rejected",Math.max(0,offered-imported));
        }
        row.put("merge_policy","APPEND_NEW_KEEP_EXISTING_RECORD_CONFLICTS");
        row.put("excluded","Gmail/provider/model/voice tokens; photos; private mind; other people");
        File path=new File(context.getFilesDir(),"secure_sync_import_history.jsonl");
        try(FileOutputStream output=new FileOutputStream(path,true)){
            output.write((row.toString()+"\n").getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte value:bytes)out.append(String.format("%02x",value&255));return out.toString();}
}
