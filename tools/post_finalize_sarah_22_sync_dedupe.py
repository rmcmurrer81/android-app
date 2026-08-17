#!/usr/bin/env python3
"""Make Android↔Windows synchronization stable across repeated sessions."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "Sarah_Morgan_Android_Phone_First_v3/android-app/app/src/main/java/com/kiraworld/sarahtravel"


def replace(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise RuntimeError(f"Sync-dedupe anchor missing in {path}: {old[:100]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8", newline="\n")


def main() -> None:
    db = JAVA / "SarahDatabase.java"
    replace(db,
'''        String sql = includeUnassigned
                ? "SELECT role,content,speaker_name FROM messages WHERE lower(speaker_name)=lower(?) OR speaker_name='' ORDER BY id DESC LIMIT ?"
                : "SELECT role,content,speaker_name FROM messages WHERE lower(speaker_name)=lower(?) ORDER BY id DESC LIMIT ?";
''',
'''        String sql = includeUnassigned
                ? "SELECT id,role,content,speaker_name,created_at FROM messages WHERE lower(speaker_name)=lower(?) OR speaker_name='' ORDER BY id DESC LIMIT ?"
                : "SELECT id,role,content,speaker_name,created_at FROM messages WHERE lower(speaker_name)=lower(?) ORDER BY id DESC LIMIT ?";
''')
    replace(db,
'''                row.put("role", c.getString(0));
                row.put("content", c.getString(1));
                row.put("speaker_name", c.getString(2));
''',
'''                row.put("id", String.valueOf(c.getLong(0)));
                row.put("event_id", "android-message-" + c.getLong(0));
                row.put("role", c.getString(1));
                row.put("content", c.getString(2));
                row.put("speaker_name", c.getString(3));
                row.put("created_at", String.valueOf(c.getLong(4)));
''')

    exporter = JAVA / "SarahSyncExporter.java"
    replace(exporter,
'''    public static JSONObject export(Context c)throws Exception{SarahDatabase db=new SarahDatabase(c);JSONObject out=new JSONObject();try{out.put("schema","sarah-sync-v1");out.put("device_id",TrustedDeviceStore.localDeviceId(c));out.put("created_at",System.currentTimeMillis());out.put("profile",new JSONObject(db.getProfile()));out.put("messages",array(db.recentMessages(200)));out.put("memories",array(db.listMemories(200)));out.put("trips",array(db.listTrips(100)));out.put("wishes",array(db.listWishes(100)));out.put("photos",photos(db.listPhotos(25)));MindEventStore mind=new MindEventStore(c);try{out.put("mind_events",mind.exportEncrypted(500));}finally{mind.close();}return out;}finally{db.close();}}
''',
'''    public static JSONObject export(Context c)throws Exception{SarahDatabase db=new SarahDatabase(c);JSONObject out=new JSONObject();try{Map<String,String> profile=db.getProfile();out.put("schema","sarah-sync-v1");out.put("device_id",TrustedDeviceStore.localDeviceId(c));out.put("created_at",System.currentTimeMillis());out.put("profile",new JSONObject(profile));out.put("messages",array(db.recentMessages(200)));out.put("memories",memories(db.listMemories(200)));out.put("trips",trips(db.listTrips(100)));out.put("wishes",wishes(db.listWishes(100)));out.put("photos",photos(db.listPhotos(25)));MindEventStore mind=new MindEventStore(c);try{out.put("mind_events",mind.exportEncrypted(500));}finally{mind.close();}ProactiveDiscoveryStore discoveries=new ProactiveDiscoveryStore(c);try{out.put("discoveries",array(discoveries.list(profile.getOrDefault("name","Traveler"),100)));}finally{discoveries.close();}return out;}finally{db.close();}}
''')
    replace(exporter,
'''    private static JSONArray array(List<Map<String,String>> rows){JSONArray a=new JSONArray();for(Map<String,String> r:rows)a.put(new JSONObject(r));return a;}
''',
'''    private static JSONArray array(List<Map<String,String>> rows){JSONArray a=new JSONArray();for(Map<String,String> r:rows)a.put(new JSONObject(r));return a;}
    private static JSONArray memories(List<Map<String,String>> rows){JSONArray a=new JSONArray();for(Map<String,String> r:rows){JSONObject o=new JSONObject(r);try{o.put("memory_id","android-memory-"+Math.abs((r.getOrDefault("category","")+"|"+r.getOrDefault("summary","")).hashCode()));}catch(Exception ignored){}a.put(o);}return a;}
    private static JSONArray trips(List<Map<String,String>> rows){JSONArray a=new JSONArray();for(Map<String,String> r:rows){JSONObject o=new JSONObject(r);try{o.put("trip_id","android-trip-"+Math.abs((r.getOrDefault("title","")+"|"+r.getOrDefault("destination","")).hashCode()));}catch(Exception ignored){}a.put(o);}return a;}
    private static JSONArray wishes(List<Map<String,String>> rows){JSONArray a=new JSONArray();for(Map<String,String> r:rows){JSONObject o=new JSONObject(r);try{o.put("wish_id","android-wish-"+Math.abs(r.getOrDefault("destination","").toLowerCase().hashCode()));}catch(Exception ignored){}a.put(o);}return a;}
''')

    importer = JAVA / "SarahSyncImporter.java"
    replace(importer,
'''JSONArray photos=payload.optJSONArray("photos");if(photos!=null)for(int i=0;i<photos.length();i++){JSONObject r=photos.optJSONObject(i);if(r==null||!seen.first(r.optString("photo_id",r.optString("sha256"))))continue;byte[] bytes=Base64.decode(r.optString("jpeg_base64",""),Base64.DEFAULT);if(bytes.length==0||bytes.length>4000000)continue;File dir=new File(context.getFilesDir(),"photos");dir.mkdirs();File file=new File(dir,r.optString("sha256",String.valueOf(System.nanoTime()))+".jpg");try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);}db.addPhoto(file.getAbsolutePath(),r.optString("caption","Synced trip photo"));count++;}return count;}finally{seen.close();db.close();}}
''',
'''JSONArray discoveries=payload.optJSONArray("discoveries");if(discoveries!=null){ProactiveDiscoveryStore store=new ProactiveDiscoveryStore(context);try{String speaker=db.getProfile().getOrDefault("name","Traveler");for(int i=0;i<discoveries.length();i++){JSONObject r=discoveries.optJSONObject(i);if(r==null)continue;String id=r.optString("discovery_id",r.optString("url"));if(!seen.first(id))continue;TavilyClient.Result result=new TavilyClient.Result(r.optString("title","Possible travel match"),r.optString("url",""),r.optString("summary",""));if(store.add(speaker,result,r.optString("query_text",r.optString("query","trusted sync")),r.optString("category","synced")))count++;}}finally{store.close();}}JSONArray photos=payload.optJSONArray("photos");if(photos!=null)for(int i=0;i<photos.length();i++){JSONObject r=photos.optJSONObject(i);if(r==null||!seen.first(r.optString("photo_id",r.optString("sha256"))))continue;byte[] bytes=Base64.decode(r.optString("jpeg_base64",""),Base64.DEFAULT);if(bytes.length==0||bytes.length>4000000)continue;File dir=new File(context.getFilesDir(),"photos");dir.mkdirs();File file=new File(dir,r.optString("sha256",String.valueOf(System.nanoTime()))+".jpg");try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);}db.addPhoto(file.getAbsolutePath(),r.optString("caption","Synced trip photo"));count++;}return count;}finally{seen.close();db.close();}}
''')
    print("Sarah 2.2 duplicate-safe synchronization pass completed.")

if __name__ == "__main__":
    main()
