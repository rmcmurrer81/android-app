#!/usr/bin/env python3
"""Final compatibility and privacy pass after the two Sarah 2.2 generators run."""
from pathlib import Path
import textwrap

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "Sarah_Morgan_Android_Phone_First_v3/android-app/app/src/main/java/com/kiraworld/sarahtravel"
RES = ROOT / "Sarah_Morgan_Android_Phone_First_v3/android-app/app/src/main/res/layout"
WIN = ROOT / "windows-companion"


def replace(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise RuntimeError(f"Finalize anchor missing in {path}: {old[:100]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8", newline="\n")


def write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    value = textwrap.dedent(content).lstrip("\n").rstrip() + "\n"
    if not path.exists() or path.read_text(encoding="utf-8") != value:
        path.write_text(value, encoding="utf-8", newline="\n")


def fix_windows() -> None:
    app = WIN / "sarah_windows.py"
    replace(app, "import queue\n", "import queue\nimport secrets\n")
    core = WIN / "sarah_core.py"
    replace(core,
'''def sync_encrypt(token: str, payload: str) -> str:
    key = derive_key(token, b"SarahTrustedSyncV1")
    nonce = secrets.token_bytes(12)
    encrypted = AESGCM(key).encrypt(nonce, payload.encode("utf-8"), b"SarahSync")
    return base64.b64encode(nonce).decode() + "." + base64.b64encode(encrypted).decode()


def sync_decrypt(token: str, value: str) -> str:
    first, second = value.split(".", 1)
    nonce, encrypted = base64.b64decode(first), base64.b64decode(second)
    return AESGCM(derive_key(token, b"SarahTrustedSyncV1")).decrypt(nonce, encrypted, b"SarahSync").decode("utf-8")
''',
'''def derive_sync_key(token: str) -> bytes:
    return PBKDF2HMAC(
        algorithm=hashes.SHA256(), length=32, salt=b"SarahTrustedSyncV1", iterations=120_000
    ).derive(token.encode("utf-8"))


def sync_encrypt(token: str, payload: str) -> str:
    nonce = secrets.token_bytes(12)
    encrypted = AESGCM(derive_sync_key(token)).encrypt(nonce, payload.encode("utf-8"), None)
    return base64.b64encode(nonce).decode() + "." + base64.b64encode(encrypted).decode()


def sync_decrypt(token: str, value: str) -> str:
    first, second = value.split(".", 1)
    nonce, encrypted = base64.b64decode(first), base64.b64decode(second)
    return AESGCM(derive_sync_key(token)).decrypt(nonce, encrypted, None).decode("utf-8")
''')
    replace(core,
'''            "mind_events": self.list_rows("mind_events", person_id, 1000),
''',
'''            "mind_events": self._trusted_sync_mind_events(person_id),
''')
    replace(core,
'''    def import_sync(self, payload: dict[str, Any]) -> dict[str, int]:
''',
'''    def _trusted_sync_mind_events(self, person_id: str) -> list[dict[str, Any]]:
        rows = self.list_rows("mind_events", person_id, 1000)
        result = []
        for row in rows:
            copy = dict(row)
            copy["private_mind"] = self.crypto.decrypt(copy.pop("private_enc", ""))
            copy["factual_truth"] = self.crypto.decrypt(copy.pop("factual_enc", ""))
            result.append(copy)
        return result

    def import_sync(self, payload: dict[str, Any]) -> dict[str, int]:
''')
    replace(core,
'''                private_enc = row.get("private_enc", "")
                factual_enc = row.get("factual_enc", "")
''',
'''                private_enc = self.crypto.encrypt(row.get("private_mind", "")) if "private_mind" in row else row.get("private_enc", "")
                factual_enc = self.crypto.encrypt(row.get("factual_truth", "")) if "factual_truth" in row else row.get("factual_enc", "")
''')
    server = WIN / "sarah_sync_server.py"
    replace(server,
'''from sarah_core import SarahDatabase, sync_decrypt, sync_signature
''',
'''from sarah_core import SarahDatabase, sync_decrypt, sync_encrypt, sync_signature
''')
    replace(server,
'''                        self._send(200, {"message": "Sarah synchronized the phone with this Windows companion.", "imported": counts}); return
''',
'''                        outgoing = json.dumps(outer.database.export_sync(include_photos=True), ensure_ascii=False)
                        encrypted_reply = sync_encrypt(token, outgoing)
                        self._send(200, {
                            "message": "Sarah synchronized the phone and Windows companion in both directions.",
                            "imported": counts,
                            "payload": encrypted_reply,
                            "signature": sync_signature(token, encrypted_reply),
                        }); return
''')
    replace(app,
'''        bar=ttk.Frame(self.discovery_tab);bar.pack(fill="x",padx=8,pady=8);ttk.Button(bar,text="Research now",command=self.research_now).pack(side="left");ttk.Button(bar,text="Sponsor connections",command=self.show_sponsors).pack(side="left",padx=5)
''',
'''        bar=ttk.Frame(self.discovery_tab);bar.pack(fill="x",padx=8,pady=8);ttk.Button(bar,text="Research now",command=self.research_now).pack(side="left");ttk.Button(bar,text="Nearby permission",command=self.set_nearby_permission).pack(side="left",padx=5);ttk.Button(bar,text="Sponsor connections",command=self.show_sponsors).pack(side="left",padx=5)
''')
    replace(app,
'''    def research_now(self):
''',
'''    def set_nearby_permission(self):
        enabled = messagebox.askyesno(
            "Nearby discoveries",
            "Allow Sarah to use an approximate area you type for source-backed nearby events and places? She will not use precise GPS or run nearby research when this is off.",
        )
        if not enabled:
            self.db.set_setting("nearby_discoveries", "0")
            self.status.set("Nearby proactive discovery is off.")
            return
        area = simpledialog.askstring("Approximate area", "City, state or area Sarah may use:", initialvalue=self.db.active_profile().get("hometown", ""))
        if area:
            profile = self.db.active_profile()
            self.db.ensure_profile(profile.get("name", "Traveler"), profile.get("age"), area.strip(), profile.get("interests", ""), bool(profile.get("memory_consent", 1)))
            self.db.set_setting("nearby_discoveries", "1")
            self.status.set("Nearby proactive discovery is on for the approved approximate area.")

    def research_now(self):
''')


def fix_android_mind_export() -> None:
    path = JAVA / "MindEventStore.java"
    replace(path,
'''                    row.put("spoken", c.getString(2)); row.put("private_enc", c.getString(3));
                    row.put("factual_enc", c.getString(4)); row.put("classification", c.getString(5));
''',
'''                    row.put("spoken", c.getString(2)); row.put("private_mind", MindCrypto.decrypt(c.getString(3)));
                    row.put("factual_truth", MindCrypto.decrypt(c.getString(4))); row.put("classification", c.getString(5));
''')


def add_android_importer() -> None:
    write(JAVA / "SyncSeenStore.java", r'''
package com.kiraworld.sarahtravel;
import android.content.Context;import android.database.Cursor;import android.database.sqlite.SQLiteDatabase;import android.database.sqlite.SQLiteOpenHelper;
public final class SyncSeenStore extends SQLiteOpenHelper {public SyncSeenStore(Context c){super(c,"sarah_sync_seen.db",null,1);}@Override public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE seen(event_id TEXT PRIMARY KEY,seen_at INTEGER NOT NULL)");}@Override public void onUpgrade(SQLiteDatabase db,int o,int n){}public boolean first(String id){if(id==null||id.trim().isEmpty())return true;try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM seen WHERE event_id=?",new String[]{id})){if(c.moveToFirst())return false;}getWritableDatabase().execSQL("INSERT OR IGNORE INTO seen VALUES(?,?)",new Object[]{id,System.currentTimeMillis()});return true;}}
''')
    write(JAVA / "SarahSyncImporter.java", r'''
package com.kiraworld.sarahtravel;
import android.content.Context;import android.util.Base64;import org.json.JSONArray;import org.json.JSONObject;import java.io.File;import java.io.FileOutputStream;
public final class SarahSyncImporter {private SarahSyncImporter(){}
    public static int importPayload(Context context,JSONObject payload)throws Exception{if(!"sarah-sync-v1".equals(payload.optString("schema")))throw new IllegalArgumentException("Unsupported Sarah sync schema");SarahDatabase db=new SarahDatabase(context);SyncSeenStore seen=new SyncSeenStore(context);int count=0;try{JSONArray messages=payload.optJSONArray("messages");if(messages!=null)for(int i=0;i<messages.length();i++){JSONObject r=messages.optJSONObject(i);if(r==null||!seen.first(r.optString("event_id",r.optString("id"))))continue;db.addMessage(r.optString("role","user"),r.optString("content",""));count++;}JSONArray memories=payload.optJSONArray("memories");if(memories!=null)for(int i=0;i<memories.length();i++){JSONObject r=memories.optJSONObject(i);if(r==null)continue;String id=r.optString("memory_id",r.optString("category")+"|"+r.optString("summary"));if(!seen.first(id))continue;db.addMemory(r.optString("category","memory"),r.optString("summary",""),r.optString("source","trusted sync"));count++;}JSONArray trips=payload.optJSONArray("trips");if(trips!=null)for(int i=0;i<trips.length();i++){JSONObject r=trips.optJSONObject(i);if(r==null)continue;String id=r.optString("trip_id",r.optString("title")+"|"+r.optString("destination"));if(!seen.first(id))continue;db.addTrip(r.optString("title","Trip"),r.optString("destination",""),r.optString("status","planned"),r.optString("notes",""));count++;}JSONArray wishes=payload.optJSONArray("wishes");if(wishes!=null)for(int i=0;i<wishes.length();i++){JSONObject r=wishes.optJSONObject(i);if(r==null)continue;String id=r.optString("wish_id",r.optString("destination"));if(!seen.first(id))continue;db.addWish(r.optString("destination",""),r.optString("notes",""));count++;}JSONArray mind=payload.optJSONArray("mind_events");if(mind!=null)for(int i=0;i<mind.length();i++){JSONObject r=mind.optJSONObject(i);if(r==null||!seen.first(r.optString("event_id")))continue;MindEventStore.recordLocal(context,db.getProfile().getOrDefault("name","Traveler"),r.optString("spoken",""),r.optString("private_mind",""),r.optString("factual_truth",""),r.optString("classification","UNCERTAIN_BELIEF"));count++;}JSONArray photos=payload.optJSONArray("photos");if(photos!=null)for(int i=0;i<photos.length();i++){JSONObject r=photos.optJSONObject(i);if(r==null||!seen.first(r.optString("photo_id",r.optString("sha256"))))continue;byte[] bytes=Base64.decode(r.optString("jpeg_base64",""),Base64.DEFAULT);if(bytes.length==0||bytes.length>4000000)continue;File dir=new File(context.getFilesDir(),"photos");dir.mkdirs();File file=new File(dir,r.optString("sha256",String.valueOf(System.nanoTime()))+".jpg");try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);}db.addPhoto(file.getAbsolutePath(),r.optString("caption","Synced trip photo"));count++;}return count;}finally{seen.close();db.close();}}
}
''')
    client = JAVA / "TrustedSyncClient.java"
    replace(client,
'''    public static JSONObject sync(Context c)throws Exception{String host=TrustedDeviceStore.host(c),token=TrustedDeviceStore.token(c);if(host.isEmpty()||token.isEmpty())throw new IllegalStateException("Pair with the Windows companion first.");String encrypted=TrustedSyncProtocol.encrypt(token,SarahSyncExporter.export(c).toString());JSONObject body=new JSONObject();body.put("payload",encrypted);body.put("signature",TrustedSyncProtocol.signature(token,encrypted));return post("http://"+host+":8769/sync",body.toString(),token);}
''',
'''    public static JSONObject sync(Context c)throws Exception{String host=TrustedDeviceStore.host(c),token=TrustedDeviceStore.token(c);if(host.isEmpty()||token.isEmpty())throw new IllegalStateException("Pair with the Windows companion first.");String encrypted=TrustedSyncProtocol.encrypt(token,SarahSyncExporter.export(c).toString());JSONObject body=new JSONObject();body.put("payload",encrypted);body.put("signature",TrustedSyncProtocol.signature(token,encrypted));JSONObject response=post("http://"+host+":8769/sync",body.toString(),token);String incoming=response.optString("payload","");if(!incoming.isEmpty()){String signature=response.optString("signature","");if(!TrustedSyncProtocol.signature(token,incoming).equals(signature))throw new SecurityException("Windows reply signature failed");int imported=SarahSyncImporter.importPayload(c,new JSONObject(TrustedSyncProtocol.decrypt(token,incoming)));response.put("message",response.optString("message","Sync completed.")+" Android imported "+imported+" new item(s).");}return response;}
''')


def add_android_area_setting() -> None:
    settings = JAVA / "SettingsActivity.java"
    replace(settings,
'''        CheckBox nearbyDiscoveries = findViewById(R.id.nearbyDiscoveryCheck);
''',
'''        CheckBox nearbyDiscoveries = findViewById(R.id.nearbyDiscoveryCheck);
        android.widget.EditText nearbyArea = findViewById(R.id.nearbyAreaInput);
''')
    replace(settings,
'''        nearbyDiscoveries.setChecked(preferences.getBoolean("nearby_discoveries", false));
''',
'''        nearbyDiscoveries.setChecked(preferences.getBoolean("nearby_discoveries", false));
        nearbyArea.setText(preferences.getString("nearby_area", ""));
''')
    replace(settings,
'''                    .putBoolean("nearby_discoveries", nearbyDiscoveries.isChecked())
''',
'''                    .putBoolean("nearby_discoveries", nearbyDiscoveries.isChecked())
                    .putString("nearby_area", nearbyArea.getText().toString().trim())
''')
    layout = RES / "activity_settings.xml"
    replace(layout,
'''        <CheckBox
            android:id="@+id/mediaPreviewCheck"
''',
'''        <EditText
            android:id="@+id/nearbyAreaInput"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="Approved approximate area, such as Newark, NJ"
            android:inputType="textCapWords" />

        <CheckBox
            android:id="@+id/mediaPreviewCheck"
''')
    coordinator = JAVA / "ProactiveDiscoveryCoordinator.java"
    replace(coordinator,
'''        String area=nearby?profile.getOrDefault("hometown",""):"";
''',
'''        String area=nearby?prefs.getString("nearby_area",profile.getOrDefault("hometown","")):"";
''')


def main() -> None:
    fix_windows()
    fix_android_mind_export()
    add_android_importer()
    add_android_area_setting()
    print("Sarah 2.2 generated sources finalized.")

if __name__ == "__main__":
    main()
