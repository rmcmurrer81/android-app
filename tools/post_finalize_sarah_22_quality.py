#!/usr/bin/env python3
"""Final quality pass for Sarah 2.2.

Run after the Android and Windows generators.  This pass closes the remaining
continuity gaps: multiple trusted computers, automatic best-effort sync,
portable private-mind keys, neutral Windows onboarding, and Google Drive
recovery of encrypted archives.
"""
from __future__ import annotations
from pathlib import Path
import textwrap

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "Sarah_Morgan_Android_Phone_First_v3/android-app/app/src/main/java/com/kiraworld/sarahtravel"
LAYOUT = ROOT / "Sarah_Morgan_Android_Phone_First_v3/android-app/app/src/main/res/layout"
WIN = ROOT / "windows-companion"


def replace(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise RuntimeError(f"Quality-pass anchor missing in {path}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8", newline="\n")


def write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    value = textwrap.dedent(content).lstrip("\n").rstrip() + "\n"
    if not path.exists() or path.read_text(encoding="utf-8") != value:
        path.write_text(value, encoding="utf-8", newline="\n")


def windows_quality() -> None:
    core = WIN / "sarah_core.py"
    replace(core,
'''def safe_text(value: Any) -> str:
    return "" if value is None else str(value).strip()
''',
'''def safe_text(value: Any) -> str:
    return "" if value is None else str(value).strip()


def as_bool(value: Any, default: bool = False) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value != 0
    return safe_text(value).lower() in {"1", "true", "yes", "y", "on", "allowed"}
''')
    replace(core,
'''                self.ensure_profile("Robert", 45, "Newark, New Jersey", "", True, db=db)
''',
'''                self.ensure_profile("Traveler", None, "", "", True, db=db)
''')
    replace(core,
'''        person_id = self.ensure_profile(profile.get("name", "Traveler"), profile.get("age"), profile.get("hometown", ""), profile.get("interests", ""), bool(profile.get("memory_consent", 1)))
''',
'''        person_id = self.ensure_profile(
            profile.get("name", "Traveler"), profile.get("age"),
            profile.get("hometown", ""), profile.get("interests", ""),
            as_bool(profile.get("memory_consent", 1), True),
        )
''')
    replace(core,
'''            shutil.copy2(self.path, temp_path / "sarah_windows.db")
            photos = temp_path / "photos"
''',
'''            snapshot = sqlite3.connect(temp_path / "sarah_windows.db")
            try:
                with self.connect() as source:
                    source.backup(snapshot)
            finally:
                snapshot.close()
            shutil.copy2(self.root / "device.key", temp_path / "device.key")
            photos = temp_path / "photos"
''')
    replace(core,
'''            shutil.copy2(db_path, self.path)
            source_photos = restored / "photos"
''',
'''            shutil.copy2(db_path, self.path)
            restored_key = restored / "device.key"
            if not restored_key.is_file():
                raise ValueError("Archive is missing Sarah's private-mind encryption key")
            shutil.copy2(restored_key, self.root / "device.key")
            source_photos = restored / "photos"
''')

    write(WIN / "google_drive_backup.py", r'''
from __future__ import annotations
import io
from pathlib import Path

SCOPES = ["https://www.googleapis.com/auth/drive.appdata"]


def _service(client_secret: Path):
    try:
        from google_auth_oauthlib.flow import InstalledAppFlow
        from googleapiclient.discovery import build
    except ImportError as exc:
        raise RuntimeError("Install the optional Google Drive packages first") from exc
    if not Path(client_secret).is_file():
        raise FileNotFoundError("Select the Google OAuth desktop client JSON file")
    flow = InstalledAppFlow.from_client_secrets_file(str(client_secret), SCOPES)
    credentials = flow.run_local_server(port=0)
    return build("drive", "v3", credentials=credentials)


def upload_encrypted_backup(backup_path: Path, client_secret: Path) -> str:
    """Upload an already-encrypted .sarahmind archive to Drive appDataFolder."""
    from googleapiclient.http import MediaFileUpload
    service = _service(client_secret)
    metadata = {"name": Path(backup_path).name, "parents": ["appDataFolder"]}
    media = MediaFileUpload(str(backup_path), mimetype="application/octet-stream", resumable=True)
    result = service.files().create(body=metadata, media_body=media, fields="id,name").execute()
    return result["id"]


def download_latest_encrypted_backup(destination: Path, client_secret: Path) -> Path:
    """Download the newest encrypted Sarah archive from Drive appDataFolder."""
    from googleapiclient.http import MediaIoBaseDownload
    service = _service(client_secret)
    result = service.files().list(
        spaces="appDataFolder",
        q="name contains '.sarahmind' and trashed = false",
        orderBy="modifiedTime desc",
        pageSize=10,
        fields="files(id,name,modifiedTime,size)",
    ).execute()
    files = result.get("files", [])
    if not files:
        raise FileNotFoundError("No encrypted Sarah mind archive was found in this Google account")
    newest = files[0]
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    request = service.files().get_media(fileId=newest["id"])
    with destination.open("wb") as output:
        downloader = MediaIoBaseDownload(output, request)
        done = False
        while not done:
            _status, done = downloader.next_chunk()
    return destination
''')

    app = WIN / "sarah_windows.py"
    replace(app,
'''        self._build_ui()
        self._start_corner()
''',
'''        self._build_ui()
        self.root.after(500, self._maybe_onboard)
        self._start_corner()
''')
    replace(app,
'''    def _build_ui(self):
''',
'''    def _maybe_onboard(self):
        profile = self.db.active_profile()
        if profile.get("name") != "Traveler" or self.db.recent_messages(1):
            return
        name = simpledialog.askstring(
            "Meet Sarah",
            "What name should Sarah use on this computer? You may also cancel and pair your phone first.",
            parent=self.root,
        )
        if not name:
            return
        age_text = simpledialog.askstring("Age", "Your age or birth year (optional):", parent=self.root) or ""
        age = None
        try:
            number = int(age_text.strip())
            age = time.localtime().tm_year - number if number >= 1900 else number
            if age < 1 or age > 120:
                age = None
        except Exception:
            pass
        hometown = simpledialog.askstring(
            "Home or approximate area",
            "City/state/country (optional; nearby proactive research stays off until separately enabled):",
            parent=self.root,
        ) or ""
        interests = simpledialog.askstring(
            "Interests",
            "Interests Sarah may use for trip ideas and trivia (optional):",
            parent=self.root,
        ) or ""
        self.db.ensure_profile(name.strip(), age, hometown.strip(), interests.strip(), True)
        self.status.set("Sarah's Windows profile is ready. Pair the phone to continue one shared Sarah.")

    def _build_ui(self):
''')
    replace(app,
'''        profile = self.db.active_profile()
        fixed = corrected_name(text)
''',
'''        profile = self.db.active_profile()
        fixed = corrected_name(text)
''')
    replace(app,
'''        self._append(profile.get("name", "You"), text); self.db.add_message("user", text)
''',
'''        profile = self.db.active_profile()
        self._append(profile.get("name", "You"), text); self.db.add_message("user", text)
''')
    replace(app,
'''        from google_drive_backup import upload_encrypted_backup
''',
'''        from google_drive_backup import upload_encrypted_backup
''')
    replace(app,
'''        frame=ttk.Frame(self.device_tab,padding=14);frame.pack(fill="both",expand=True);self.pair_var=tk.StringVar(value=self.sync_server.pairing_code);ttk.Label(frame,text="Phone pairing code",font=("Segoe UI",14,"bold")).pack(anchor="w");ttk.Label(frame,textvariable=self.pair_var,font=("Consolas",28,"bold")).pack(anchor="w");ttk.Label(frame,text=f"Windows address: {self.local_ip()}:8769\\nEnter this address and the code on Sarah's Android Devices & Photos screen. Pair only on trusted Wi-Fi.").pack(anchor="w",pady=8);ttk.Button(frame,text="Rotate pairing code",command=self.rotate_code).pack(anchor="w");ttk.Button(frame,text="Export encrypted .sarahmind backup",command=self.backup).pack(anchor="w",pady=5);ttk.Button(frame,text="Restore encrypted backup",command=self.restore).pack(anchor="w");ttk.Button(frame,text="Upload encrypted backup to Google Drive appDataFolder",command=self.drive_backup).pack(anchor="w",pady=5);ttk.Button(frame,text="Revoke a paired device",command=self.revoke_device).pack(anchor="w")
''',
'''        frame=ttk.Frame(self.device_tab,padding=14);frame.pack(fill="both",expand=True);self.pair_var=tk.StringVar(value=self.sync_server.pairing_code);ttk.Label(frame,text="Phone pairing code",font=("Segoe UI",14,"bold")).pack(anchor="w");ttk.Label(frame,textvariable=self.pair_var,font=("Consolas",28,"bold")).pack(anchor="w");ttk.Label(frame,text=f"Windows address: {self.local_ip()}:8769\\nEnter this address and the code on Sarah's Android Devices & Photos screen. Pair only on trusted Wi-Fi.").pack(anchor="w",pady=8);ttk.Button(frame,text="Rotate pairing code",command=self.rotate_code).pack(anchor="w");ttk.Button(frame,text="Export encrypted .sarahmind backup",command=self.backup).pack(anchor="w",pady=5);ttk.Button(frame,text="Restore encrypted backup from this computer",command=self.restore).pack(anchor="w");ttk.Button(frame,text="Upload encrypted backup to Google Drive appDataFolder",command=self.drive_backup).pack(anchor="w",pady=5);ttk.Button(frame,text="Download and restore newest encrypted Google Drive backup",command=self.drive_restore).pack(anchor="w");ttk.Button(frame,text="Revoke a paired device",command=self.revoke_device).pack(anchor="w",pady=5)
''')
    replace(app,
'''    def revoke_device(self):
''',
'''    def drive_restore(self):
        from google_drive_backup import download_latest_encrypted_backup
        client=filedialog.askopenfilename(title="Select Google OAuth desktop client JSON",filetypes=[("JSON","*.json")])
        if not client:return
        password=simpledialog.askstring("Backup password","Enter the archive password after it downloads.",show="*")
        if not password:return
        try:
            destination=app_home()/"backups"/"latest-google-drive.sarahmind"
            download_latest_encrypted_backup(destination,Path(client))
            self.db.restore_backup(destination,password)
            messagebox.showinfo("Drive restore completed","Sarah's newest encrypted Drive archive was restored. Restart the companion so the restored private encryption key is loaded.")
        except Exception as exc:messagebox.showerror("Drive restore failed",str(exc))

    def revoke_device(self):
''')


def android_multi_device() -> None:
    write(JAVA / "TrustedDeviceStore.java", r'''
package com.kiraworld.sarahtravel;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/** Stores several explicitly paired Sarah computers; Wi-Fi alone never grants trust. */
public final class TrustedDeviceStore {
    private static final String PREFS="sarah_trusted_devices";
    private static final String PEERS="peers_json";
    private static final String SELECTED="selected_host";
    private TrustedDeviceStore(){}

    public static String localDeviceId(Context c){
        SharedPreferences p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        String id=p.getString("local_device_id","");
        if(id.isEmpty()){id=UUID.randomUUID().toString();p.edit().putString("local_device_id",id).apply();}
        return id;
    }

    public static synchronized void savePeer(Context c,String host,String token){
        host=clean(host); token=clean(token); if(host.isEmpty()||token.isEmpty())return;
        JSONObject peers=read(c); JSONObject peer=new JSONObject();
        try{peer.put("host",host);peer.put("token",token);peer.put("paired_at",System.currentTimeMillis());peers.put(host,peer);}catch(Exception ignored){}
        write(c,peers);c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(SELECTED,host).apply();
    }

    public static synchronized List<String> hosts(Context c){
        List<String> result=new ArrayList<>();Iterator<String> keys=read(c).keys();while(keys.hasNext())result.add(keys.next());return result;
    }

    public static synchronized String tokenFor(Context c,String host){
        JSONObject peer=read(c).optJSONObject(clean(host));return peer==null?"":peer.optString("token","");
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
        JSONObject peers=read(c);peers.remove(clean(host));write(c,peers);
        SharedPreferences p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        if(clean(host).equals(p.getString(SELECTED,"")))p.edit().remove(SELECTED).apply();
    }
    public static void revoke(Context c){revoke(c,host(c));}

    private static JSONObject read(Context c){
        try{return new JSONObject(c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(PEERS,"{}"));}
        catch(Exception ignored){return new JSONObject();}
    }
    private static void write(Context c,JSONObject value){c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(PEERS,value.toString()).apply();}
    private static String clean(String v){return v==null?"":v.trim();}
}
''')
    write(JAVA / "TrustedSyncClient.java", r'''
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
''')
    write(JAVA / "TrustedSyncActivity.java", r'''
package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

public final class TrustedSyncActivity extends Activity {
    private TextView status;
    private Spinner peers;
    private ArrayAdapter<String> peerAdapter;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(30,30,30,30);scroll.addView(root);
        TextView title=new TextView(this);title.setText("Trusted Sarah devices and trip photos");title.setTextSize(26);root.addView(title);
        TextView note=new TextView(this);note.setText("Pair only with your own Sarah desktop or laptop on trusted private Wi-Fi. Sharing Wi-Fi does not grant trust: Windows must show a matching six-digit code. Every payload is encrypted and signed. Sarah can remember several computers and synchronize each one.");note.setPadding(0,12,0,16);root.addView(note);
        EditText host=new EditText(this);host.setHint("Windows address, for example 192.168.1.25");root.addView(host);
        EditText code=new EditText(this);code.setHint("Six-digit code shown by that Windows Sarah");code.setInputType(InputType.TYPE_CLASS_NUMBER);root.addView(code);
        Button pair=new Button(this);pair.setText("Verify and pair this computer");pair.setOnClickListener(v->run(()->{TrustedSyncClient.pair(this,host.getText().toString().trim(),code.getText().toString().trim());runOnUiThread(this::refreshPeers);return "The computer was paired. It will not receive anything from a matching Wi-Fi network without this approval.";}));root.addView(pair);
        TextView saved=new TextView(this);saved.setText("Saved Sarah computers");saved.setPadding(0,18,0,5);root.addView(saved);
        peers=new Spinner(this);peerAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new ArrayList<>());peers.setAdapter(peerAdapter);root.addView(peers);
        status=new TextView(this);status.setPadding(0,12,0,12);root.addView(status);
        Button selected=new Button(this);selected.setText("Sync selected computer now");selected.setOnClickListener(v->{String h=selectedHost();if(h.isEmpty()){status.setText("No computer is paired.");return;}TrustedDeviceStore.select(this,h);run(()->TrustedSyncClient.syncHost(this,h).optString("message","Sync completed."));});root.addView(selected);
        Button all=new Button(this);all.setText("Sync desktop and laptop now");all.setOnClickListener(v->run(()->TrustedSyncClient.syncAll(this).optString("message","Sync completed.")));root.addView(all);
        Button revoke=new Button(this);revoke.setText("Revoke selected computer");revoke.setOnClickListener(v->{String h=selectedHost();if(!h.isEmpty())TrustedDeviceStore.revoke(this,h);refreshPeers();status.setText("That computer's saved token was revoked on this phone.");});root.addView(revoke);
        refreshPeers();setContentView(scroll);
    }

    private void refreshPeers(){List<String> list=TrustedDeviceStore.hosts(this);peerAdapter.clear();peerAdapter.addAll(list);peerAdapter.notifyDataSetChanged();String selected=TrustedDeviceStore.host(this);int index=list.indexOf(selected);if(index>=0)peers.setSelection(index);}
    private String selectedHost(){Object value=peers.getSelectedItem();return value==null?"":value.toString();}
    private interface Work{String run()throws Exception;}
    private void run(Work work){status.setText("Working…");new Thread(()->{try{String result=work.run();runOnUiThread(()->status.setText(result));}catch(Exception e){runOnUiThread(()->status.setText("Could not complete: "+e.getMessage()));}},"Sarah-Trusted-Sync").start();}
}
''')

    main = JAVA / "MainActivity.java"
    replace(main,
'''        speak(reply);
    }
''',
'''        speak(reply);
        TrustedSyncClient.syncAllAsync(this);
    }
''')
    replace(main,
'''            speak(speakerResult.reply);
            return;
''',
'''            speak(speakerResult.reply);
            TrustedSyncClient.syncAllAsync(this);
            return;
''')
    replace(main,
'''                    speak(finalReply);
                } else {
''',
'''                    speak(finalReply);
                    TrustedSyncClient.syncAllAsync(this);
                } else {
''')

    settings = JAVA / "SettingsActivity.java"
    replace(settings,
'''        CheckBox learn = findViewById(R.id.learnCheck);
''',
'''        CheckBox learn = findViewById(R.id.learnCheck);
        CheckBox autoDeviceSync = findViewById(R.id.autoDeviceSyncCheck);
''')
    replace(settings,
'''        learn.setChecked(preferences.getBoolean("learn", true));
''',
'''        learn.setChecked(preferences.getBoolean("learn", true));
        autoDeviceSync.setChecked(preferences.getBoolean("auto_device_sync", true));
''')
    replace(settings,
'''                    .putBoolean("learn", learn.isChecked())
''',
'''                    .putBoolean("learn", learn.isChecked())
                    .putBoolean("auto_device_sync", autoDeviceSync.isChecked())
''')
    layout = LAYOUT / "activity_settings.xml"
    replace(layout,
'''        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="20dp"
            android:text="Memory"
''',
'''        <CheckBox
            android:id="@+id/autoDeviceSyncCheck"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:checked="true"
            android:text="After Sarah replies, quietly synchronize with every explicitly paired desktop or laptop that is available on this trusted Wi-Fi network" />

        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="20dp"
            android:text="Memory"
''')


def docs_quality() -> None:
    path = ROOT / "docs/SYNC_BACKUP_AND_PRIVACY.md"
    text = path.read_text(encoding="utf-8")
    addition = '''

## Desktop + laptop + phone

Android stores several separately approved Windows peers. After a reply, best-effort automatic sync can contact the desktop and laptop independently; an unavailable computer does not block the other one. Each Windows companion has its own revocable token. The phone can also run **Sync desktop and laptop now** manually.

The encrypted Windows archive contains both the SQLite snapshot and Sarah's device encryption key. Without that key, restored private-mind and factual records would be unreadable. Google Drive receives only the already password-encrypted `.sarahmind` archive. A clean Windows installation can download the newest archive, decrypt it locally with the owner's password, restart Sarah, and then pair a replacement phone.
'''
    if "## Desktop + laptop + phone" not in text:
        path.write_text(text.rstrip()+addition+"\n",encoding="utf-8",newline="\n")


def main() -> None:
    windows_quality()
    android_multi_device()
    docs_quality()
    print("Sarah 2.2 final quality pass completed.")

if __name__ == "__main__":
    main()
