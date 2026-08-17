# Sarah synchronization, backup and privacy

Same-Wi-Fi discovery is not automatic trust. A Windows companion displays a temporary six-digit pairing code; the Android owner enters that code and explicitly approves the device. A per-device token is then used to encrypt and sign every sync payload. Pair only on a trusted private network. The prototype uses local HTTP because the payload itself is AES-GCM encrypted; a public release should add certificate pinning or a protected relay.

Sync uses append-only event IDs so desktop, laptop and phone can merge rather than overwrite one another. SPOKEN may appear in history. PRIVATE MIND and FACTUAL TRUTH remain encrypted records; private mind is never rendered as ordinary chat or sent to TTS.

Windows can create a password-encrypted `.sarahmind` archive. Optional Google Drive support uploads only that already-encrypted archive to the user's Drive `appDataFolder` after the owner supplies a Google OAuth desktop client file. Gmail is suitable for a security notification, not as Sarah's mind database.

The owner can revoke any paired device. Passwords, payment-card details, booking-site credentials, provider API keys and recovery codes are excluded from sync and memory.

## Desktop + laptop + phone

Android stores several separately approved Windows peers. After a reply, best-effort automatic sync can contact the desktop and laptop independently; an unavailable computer does not block the other one. Each Windows companion has its own revocable token. The phone can also run **Sync desktop and laptop now** manually.

The encrypted Windows archive contains both the SQLite snapshot and Sarah's device encryption key. Without that key, restored private-mind and factual records would be unreadable. Google Drive receives only the already password-encrypted `.sarahmind` archive. A clean Windows installation can download the newest archive, decrypt it locally with the owner's password, restart Sarah, and then pair a replacement phone.

