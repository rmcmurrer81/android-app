from __future__ import annotations
import json
import os
from pathlib import Path
import queue
import secrets
import socket
import subprocess
import sys
import threading
import time
import tkinter as tk
from tkinter import filedialog, messagebox, simpledialog, ttk
import webbrowser

from PIL import Image, ImageDraw, ImageTk

from sarah_core import (
    ChannelResponse, ElevenLabsVoice, ModelClient, SarahDatabase, TavilyResearch,
    app_home, corrected_name, discovery_queries, is_stress_or_fear,
    load_runtime_config, safe_text, save_runtime_config,
)
from sarah_sync_server import SarahSyncServer

try:
    import pystray
except Exception:
    pystray = None
try:
    from playsound3 import playsound
except Exception:
    playsound = None


# Windows notification area / hidden-icons support keeps Sarah active when her windows are hidden.
class SarahApp:
    def __init__(self):
        self.root = tk.Tk()
        self.root.title("Sarah Morgan Windows Companion")
        self.root.geometry("1080x760")
        self.root.minsize(820, 580)
        self.db = SarahDatabase()
        self.model = ModelClient(self.db)
        self.research = TavilyResearch()
        self.voice = ElevenLabsVoice()
        self.sync_server = SarahSyncServer(self.db)
        self.sync_server.start()
        self.tasks: queue.Queue[tuple[str, object]] = queue.Queue()
        self.speaking = False
        self.blink = False
        self.tray = None
        self.corner: tk.Toplevel | None = None
        self._build_ui()
        self.root.after(500, self._maybe_onboard)
        self._start_corner()
        self._start_tray()
        self.root.after(100, self._poll_tasks)
        self.root.after(4000, self._idle_research_tick)
        self.root.protocol("WM_DELETE_WINDOW", self.hide_to_tray)

    def _maybe_onboard(self):
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
        style = ttk.Style(self.root)
        try: style.theme_use("vista")
        except tk.TclError: pass
        header = tk.Frame(self.root, bg="#183448", height=76)
        header.pack(fill="x")
        tk.Label(header, text="Sarah Morgan", fg="white", bg="#183448", font=("Segoe UI", 24, "bold")).pack(side="left", padx=20, pady=15)
        self.status = tk.StringVar(value="Ready • phone pairing code " + self.sync_server.pairing_code)
        tk.Label(header, textvariable=self.status, fg="#d9edf7", bg="#183448", font=("Segoe UI", 10)).pack(side="right", padx=20)
        ttk.Button(header, text="Online setup", command=self.configure_online_services).pack(side="right", padx=6, pady=15)
        self.tabs = ttk.Notebook(self.root); self.tabs.pack(fill="both", expand=True, padx=10, pady=10)
        self.chat_tab = ttk.Frame(self.tabs); self.discovery_tab = ttk.Frame(self.tabs); self.trip_tab = ttk.Frame(self.tabs); self.photo_tab = ttk.Frame(self.tabs); self.device_tab = ttk.Frame(self.tabs); self.activity_tab = ttk.Frame(self.tabs)
        self.tabs.add(self.chat_tab, text="Talk with Sarah"); self.tabs.add(self.discovery_tab, text="Discoveries"); self.tabs.add(self.trip_tab, text="Trips"); self.tabs.add(self.photo_tab, text="Photos"); self.tabs.add(self.device_tab, text="Devices & backup"); self.tabs.add(self.activity_tab, text="Factual activity")
        self._build_chat(); self._build_discoveries(); self._build_trips(); self._build_photos(); self._build_devices(); self._build_activity()

    def _build_chat(self):
        self.chat = tk.Text(self.chat_tab, wrap="word", font=("Segoe UI", 12), bg="#fbf8f1", padx=16, pady=16, state="disabled")
        self.chat.pack(fill="both", expand=True, padx=8, pady=8)
        row = ttk.Frame(self.chat_tab); row.pack(fill="x", padx=8, pady=(0,8))
        self.entry = ttk.Entry(row, font=("Segoe UI", 12)); self.entry.pack(side="left", fill="x", expand=True); self.entry.bind("<Return>", lambda _e: self.send())
        ttk.Button(row, text="Send", command=self.send).pack(side="left", padx=5)
        ttk.Button(row, text="Calm choices", command=lambda: self._submit_text("I am stressed and I need calm choices")).pack(side="left")
        self._append("Sarah", "I’m here. We can plan a trip, talk about ordinary life, organize your travel photos, or do absolutely nothing travel-related.")

    def _append(self, who: str, text: str):
        self.chat.configure(state="normal"); self.chat.insert("end", f"{who}\n{text}\n\n"); self.chat.see("end"); self.chat.configure(state="disabled")

    def configure_online_services(self):
        """Save team deployment settings per user, never inside the EXE or repository."""
        current = load_runtime_config(self.db.root)
        backend = simpledialog.askstring(
            "Sarah online setup",
            "Protected Sarah backend HTTPS URL (blank removes the per-user override; event builds then use their bundled route):",
            initialvalue=current.get("SARAH_MODEL_BACKEND_URL", ""),
            parent=self.root,
        )
        if backend is None:
            return
        token = simpledialog.askstring(
            "Sarah online setup",
            "Backend token. Leave blank to keep the saved token; type CLEAR to remove it:",
            show="*",
            parent=self.root,
        )
        voice_key = simpledialog.askstring(
            "Sarah voice setup",
            "Optional direct-test ElevenLabs key. The protected Sarah backend does not need this. Leave blank to keep it; type CLEAR to remove it:",
            show="*",
            parent=self.root,
        )
        voice_id = simpledialog.askstring(
            "Sarah voice setup",
            "Optional approved Sarah ElevenLabs voice ID (blank keeps the saved value):",
            initialvalue=current.get("SARAH_ELEVENLABS_VOICE_ID", ""),
            parent=self.root,
        )

        updated = dict(current)
        if safe_text(backend):
            updated["SARAH_MODEL_BACKEND_URL"] = safe_text(backend)
            updated.setdefault("SARAH_MODEL_PROVIDER", "workers-ai")
            updated.setdefault("SARAH_MODEL_ID", "@cf/google/gemma-4-26b-a4b-it")
        else:
            updated.pop("SARAH_MODEL_BACKEND_URL", None)
        self._update_secret(updated, "SARAH_MODEL_BACKEND_TOKEN", token)
        self._update_secret(updated, "SARAH_ELEVENLABS_API_KEY", voice_key)
        if voice_id is not None and safe_text(voice_id):
            updated["SARAH_ELEVENLABS_VOICE_ID"] = safe_text(voice_id)
        try:
            save_runtime_config(updated, self.db.root)
        except ValueError as error:
            messagebox.showerror("Sarah online setup", str(error), parent=self.root)
            return
        self.voice = ElevenLabsVoice(self.db.root)
        self.research = TavilyResearch()
        self.status.set("Online settings saved per user • no provider key was embedded in Sarah")

    @staticmethod
    def _update_secret(settings: dict[str, str], name: str, value: str | None) -> None:
        normalized = safe_text(value)
        if normalized.upper() == "CLEAR":
            settings.pop(name, None)
        elif normalized:
            settings[name] = normalized

    def send(self):
        text = self.entry.get().strip()
        if not text: return
        self.entry.delete(0, "end"); self._submit_text(text)

    def _submit_text(self, text: str):
        profile = self.db.active_profile()
        fixed = corrected_name(text)
        if fixed and (text.lower().startswith(("no", "actually", "sorry", "wait")) or fixed.lower() == profile.get("name", "").lower()):
            self.db.ensure_profile(fixed, profile.get("age"), profile.get("hometown", ""), profile.get("interests", ""), bool(profile.get("memory_consent", 1)))
        profile = self.db.active_profile()
        self._append(profile.get("name", "You"), text); self.db.add_message("user", text)
        self.status.set("Sarah is thinking…")
        threading.Thread(target=self._answer, args=(text,), daemon=True).start()

    def _answer(self, text: str):
        try: response = self.model.respond(text)
        except Exception as exc: response = ChannelResponse("I ran into a connection problem, so I’m staying local. " + str(exc), "Sarah is preserving continuity after a tool error.", "The connected response failed; no external action was completed.", "RUNTIME_STATE_ERROR", True)
        self.db.add_message("assistant", response.spoken); self.db.add_mind_event(response, "windows-chat")
        self.tasks.put(("reply", response))

    def _poll_tasks(self):
        try:
            while True:
                kind, payload = self.tasks.get_nowait()
                if kind == "reply":
                    response: ChannelResponse = payload
                    self._append("Sarah", response.spoken); self.status.set("Ready • only SPOKEN is visible"); self._speak(response.spoken); self.refresh_activity()
                elif kind == "research": self.status.set(str(payload)); self.refresh_discoveries()
        except queue.Empty: pass
        self.root.after(100, self._poll_tasks)

    def _speak(self, text: str):
        threading.Thread(target=self._speak_worker, args=(text,), daemon=True).start()

    def _speak_worker(self, text: str):
        self.speaking = True
        try:
            if self.voice.configured and playsound:
                playsound(str(self.voice.synthesize(text)), block=True)
            elif sys.platform.startswith("win"):
                escaped = text.replace("'", "''")
                subprocess.run(["powershell", "-NoProfile", "-Command", f"Add-Type -AssemblyName System.Speech; $s=New-Object System.Speech.Synthesis.SpeechSynthesizer; $s.Rate=-1; $s.Speak('{escaped}')"], check=False, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        finally: self.speaking = False

    def _build_discoveries(self):
        bar=ttk.Frame(self.discovery_tab);bar.pack(fill="x",padx=8,pady=8);ttk.Button(bar,text="Research now",command=self.research_now).pack(side="left");ttk.Button(bar,text="Nearby permission",command=self.set_nearby_permission).pack(side="left",padx=5);ttk.Button(bar,text="Sponsor connections",command=self.show_sponsors).pack(side="left",padx=5)
        self.discovery_list=tk.Listbox(self.discovery_tab,font=("Segoe UI",11));self.discovery_list.pack(fill="both",expand=True,padx=8,pady=8);self.discovery_list.bind("<Double-1>",self.open_discovery);self.discovery_rows=[];self.refresh_discoveries()

    def set_nearby_permission(self):
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
        if not self.research.configured: messagebox.showinfo("Tavily not configured","Set SARAH_TAVILY_API_KEY for source-backed proactive research. Sarah will not pretend research happened.");return
        threading.Thread(target=self._research_worker,daemon=True).start()

    def _research_worker(self):
        profile=self.db.active_profile();trips=self.db.list_rows("trips",limit=20);nearby=self.db.get_setting("nearby_discoveries","0")=="1";added=0
        for query in discovery_queries(profile,trips,nearby):
            for result in self.research.search(query,5): added+=self.db.add_discovery(result["title"],result["summary"],result["url"],query)
        self.tasks.put(("research",f"Research finished • {added} new source-backed match(es)"))

    def refresh_discoveries(self):
        self.discovery_rows=self.db.list_rows("discoveries",limit=100);self.discovery_list.delete(0,"end")
        for row in self.discovery_rows:self.discovery_list.insert("end",f"{row['title']} — {row['source']}")
    def open_discovery(self,_event=None):
        sel=self.discovery_list.curselection();
        if sel:webbrowser.open(self.discovery_rows[sel[0]]["url"])
    def show_sponsors(self): messagebox.showinfo("Travel Hack NYC connections","ElevenLabs: connected Sarah voice with offline fallback.\nTavily: source-backed proactive discovery.\nStay22: accommodation handoff.\nRove: rewards-aware official handoff.\nAeroXplorer: sourced aviation context.\nPropellic and Lovable: destination-marketing and presentation alignment.\n\nNo search or handoff is called a completed booking.")

    def _build_trips(self):
        form=ttk.Frame(self.trip_tab);form.pack(fill="x",padx=8,pady=8);self.trip_title=ttk.Entry(form);self.trip_dest=ttk.Entry(form);self.trip_title.insert(0,"My trip");self.trip_title.pack(side="left",fill="x",expand=True);self.trip_dest.pack(side="left",fill="x",expand=True,padx=5);ttk.Button(form,text="Save planned trip",command=self.add_trip).pack(side="left")
        self.trip_list=tk.Listbox(self.trip_tab,font=("Segoe UI",11));self.trip_list.pack(fill="both",expand=True,padx=8,pady=8);self.refresh_trips()
    def add_trip(self):
        dest=self.trip_dest.get().strip();
        if not dest:return
        self.db.add_trip(self.trip_title.get().strip() or "My trip",dest);self.refresh_trips();self.status.set("Trip saved. Sarah can now prepare interest-aware research.")
    def refresh_trips(self):
        self.trip_list.delete(0,"end")
        for row in self.db.list_rows("trips",limit=100):self.trip_list.insert("end",f"{row['status']}: {row['destination']} — {row['title']}")

    def _build_photos(self):
        bar=ttk.Frame(self.photo_tab);bar.pack(fill="x",padx=8,pady=8);ttk.Button(bar,text="Import trip photos",command=self.import_photos).pack(side="left");ttk.Button(bar,text="Open Sarah photo folder",command=lambda:os.startfile(app_home()/"photos") if hasattr(os,"startfile") else None).pack(side="left",padx=5)
        self.photo_list=tk.Listbox(self.photo_tab,font=("Segoe UI",11));self.photo_list.pack(fill="both",expand=True,padx=8,pady=8);self.refresh_photos()
    def import_photos(self):
        paths=filedialog.askopenfilenames(filetypes=[("Images","*.jpg *.jpeg *.png *.webp")]);count=0
        for path in paths:
            try:self.db.import_photo(Path(path),Path(path).stem);count+=1
            except Exception as exc:messagebox.showwarning("Photo skipped",str(exc))
        self.refresh_photos();self.status.set(f"Imported {count} sanitized, duplicate-checked photo(s).")
    def refresh_photos(self):
        self.photo_list.delete(0,"end")
        for row in self.db.list_rows("photos",limit=300):self.photo_list.insert("end",f"{row['caption']} — {row['sha256'][:10]}")

    def _build_devices(self):
        frame=ttk.Frame(self.device_tab,padding=14);frame.pack(fill="both",expand=True);self.pair_var=tk.StringVar(value=self.sync_server.pairing_code);ttk.Label(frame,text="Phone pairing code",font=("Segoe UI",14,"bold")).pack(anchor="w");ttk.Label(frame,textvariable=self.pair_var,font=("Consolas",28,"bold")).pack(anchor="w");ttk.Label(frame,text=f"Windows address: {self.local_ip()}:8769\nEnter this address and the code on Sarah's Android Devices & Photos screen. Pair only on trusted Wi-Fi.").pack(anchor="w",pady=8);ttk.Button(frame,text="Rotate pairing code",command=self.rotate_code).pack(anchor="w");ttk.Button(frame,text="Export encrypted .sarahmind backup",command=self.backup).pack(anchor="w",pady=5);ttk.Button(frame,text="Restore encrypted backup from this computer",command=self.restore).pack(anchor="w");ttk.Button(frame,text="Upload encrypted backup to Google Drive appDataFolder",command=self.drive_backup).pack(anchor="w",pady=5);ttk.Button(frame,text="Download and restore newest encrypted Google Drive backup",command=self.drive_restore).pack(anchor="w");ttk.Button(frame,text="Revoke a paired device",command=self.revoke_device).pack(anchor="w",pady=5)
    def rotate_code(self):self.pair_var.set(self.sync_server.rotate_code());self.status.set("A new 15-minute pairing code is ready.")
    def local_ip(self):
        try:s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.connect(("8.8.8.8",80));ip=s.getsockname()[0];s.close();return ip
        except Exception:return "this-computer"
    def backup(self):
        path=filedialog.asksaveasfilename(defaultextension=".sarahmind",filetypes=[("Sarah mind archive","*.sarahmind")]);
        if not path:return
        password=simpledialog.askstring("Backup password","Choose at least 10 characters. This password is not uploaded.",show="*")
        if not password:return
        try:self.db.create_backup(Path(path),password);messagebox.showinfo("Backup created","The encrypted Sarah archive was created.")
        except Exception as exc:messagebox.showerror("Backup failed",str(exc))
    def restore(self):
        path=filedialog.askopenfilename(filetypes=[("Sarah mind archive","*.sarahmind")]);
        if not path:return
        password=simpledialog.askstring("Backup password","Enter the archive password.",show="*")
        try:self.db.restore_backup(Path(path),password or "");messagebox.showinfo("Restored","Sarah's encrypted archive was restored. Restart the companion.")
        except Exception as exc:messagebox.showerror("Restore failed",str(exc))
    def drive_backup(self):
        from google_drive_backup import upload_encrypted_backup
        backup=filedialog.askopenfilename(filetypes=[("Sarah mind archive","*.sarahmind")]);
        if not backup:return
        client=filedialog.askopenfilename(title="Select Google OAuth desktop client JSON",filetypes=[("JSON","*.json")]);
        if not client:return
        try:file_id=upload_encrypted_backup(Path(backup),Path(client));messagebox.showinfo("Uploaded",f"Encrypted backup uploaded to Drive appDataFolder. File ID: {file_id}")
        except Exception as exc:messagebox.showerror("Drive upload failed",str(exc))
    def drive_restore(self):
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
        with self.db.connect() as db:rows=db.execute("SELECT device_id,device_name FROM trusted_devices WHERE revoked=0").fetchall()
        if not rows:messagebox.showinfo("Devices","No active paired devices.");return
        name=simpledialog.askstring("Revoke device","Enter the exact device name:\n"+"\n".join(r["device_name"] for r in rows))
        if name:
            with self.db.connect() as db:db.execute("UPDATE trusted_devices SET revoked=1 WHERE lower(device_name)=lower(?)",(name.strip(),))

    def _build_activity(self):
        bar=ttk.Frame(self.activity_tab);bar.pack(fill="x",padx=8,pady=8);ttk.Button(bar,text="Refresh",command=self.refresh_activity).pack(side="left");ttk.Label(bar,text="Factual truth is shown for auditing. Private mind remains hidden unless you explicitly open one event.").pack(side="left",padx=10)
        self.activity_list=tk.Listbox(self.activity_tab,font=("Segoe UI",10));self.activity_list.pack(fill="both",expand=True,padx=8,pady=8);self.activity_list.bind("<Double-1>",self.open_private_event);self.activity_rows=[];self.refresh_activity()
    def refresh_activity(self):
        self.activity_rows=self.db.visible_activity(200);self.activity_list.delete(0,"end")
        for row in self.activity_rows:self.activity_list.insert("end",f"{row['classification']} | {row['spoken'][:85]} | FACT: {row.get('factual_truth','')[:85]}")
    def open_private_event(self,_event=None):
        sel=self.activity_list.curselection();
        if not sel:return
        event=self.db.private_event(self.activity_rows[sel[0]]["event_id"]);messagebox.showinfo("Explicit private event review",f"SPOKEN:\n{event.get('spoken','')}\n\nPRIVATE MIND:\n{event.get('private_mind','')}\n\nFACTUAL TRUTH:\n{event.get('factual_truth','')}\n\nClassification: {event.get('classification','')}")

    def _start_corner(self):
        self.corner=tk.Toplevel(self.root);self.corner.title("Sarah");self.corner.geometry("180x220+20+80");self.corner.attributes("-topmost",True);self.corner.configure(bg="#183448");self.corner.overrideredirect(True);self.canvas=tk.Canvas(self.corner,width=180,height=175,bg="#183448",highlightthickness=0);self.canvas.pack();self.canvas.bind("<ButtonPress-1>",self._drag_start);self.canvas.bind("<B1-Motion>",self._drag_move);ttk.Button(self.corner,text="Talk with Sarah",command=self.show).pack(fill="x",padx=8,pady=5);self._animate_avatar()
    def _drag_start(self,e):self._dx=e.x;self._dy=e.y
    def _drag_move(self,e):self.corner.geometry(f"+{self.corner.winfo_x()+e.x-self._dx}+{self.corner.winfo_y()+e.y-self._dy}")
    def _animate_avatar(self):
        if not self.corner:return
        self.canvas.delete("all");self.canvas.create_oval(47,16,133,112,fill="#e2ad86",outline="#f2d1b8",width=2);self.canvas.create_arc(38,5,143,108,start=15,extent=150,fill="#5a382a",outline="#5a382a");self.canvas.create_arc(37,6,144,111,start=195,extent=150,fill="#5a382a",outline="#5a382a");eye_h=1 if self.blink else 6;self.canvas.create_oval(67,54,78,54+eye_h,fill="#263746",outline="");self.canvas.create_oval(102,54,113,54+eye_h,fill="#263746",outline="");mouth=8 if self.speaking and int(time.time()*5)%2 else 3;self.canvas.create_arc(78,77,103,88+mouth,start=200,extent=140,style="arc",width=2,outline="#8b3f4b");self.canvas.create_polygon(42,107,138,107,165,171,15,171,fill="#2f7897",outline="#6eb1ca");self.blink=not self.blink if secrets.randbelow(18)==0 else False;self.root.after(180,self._animate_avatar)
    def _start_tray(self):
        if not pystray:return
        image=Image.new("RGB",(64,64),"#183448");d=ImageDraw.Draw(image);d.ellipse((14,8,50,44),fill="#e2ad86");d.polygon((10,62,54,62,46,38,18,38),fill="#2f7897")
        self.tray=pystray.Icon("SarahMorgan",image,"Sarah Morgan",pystray.Menu(pystray.MenuItem("Show Sarah",lambda:self.root.after(0,self.show)),pystray.MenuItem("Quit",lambda:self.root.after(0,self.quit))))
        threading.Thread(target=self.tray.run,daemon=True).start()
    def hide_to_tray(self):self.root.withdraw();self.corner.withdraw();self.status.set("Sarah is active in hidden icons")
    def show(self):self.root.deiconify();self.root.lift();self.corner.deiconify();self.tabs.select(self.chat_tab);self.entry.focus_set()
    def _idle_research_tick(self):
        if self.db.get_setting("background_research","1")=="1" and self.research.configured:threading.Thread(target=self._research_worker,daemon=True).start()
        self.root.after(6*60*60*1000,self._idle_research_tick)
    def quit(self):
        try:self.sync_server.stop()
        finally:
            if self.tray:self.tray.stop()
            self.root.destroy()
    def run(self):self.root.mainloop()

if __name__ == "__main__":SarahApp().run()
