# Build a portable Windows executable from a Windows runner.
pyinstaller --noconfirm --clean --onefile --windowed --name SarahTravelOS-R2-Candidate --add-data 'assets\sarah_adult_portrait_r2_runtime_512.png;assets' --collect-all pystray --collect-all PIL --collect-all playsound3 --hidden-import googleapiclient.discovery --hidden-import google_auth_oauthlib.flow sarah_event_ready.py
