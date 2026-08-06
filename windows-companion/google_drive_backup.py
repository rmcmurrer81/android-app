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
