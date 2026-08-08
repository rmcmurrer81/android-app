# Sarah synchronization, backup and privacy

## R2 current boundary

Phone-to-Windows network synchronization is **not connected in the R2 owner-
acceptance candidate**. Android refuses the preserved cleartext transport,
Windows does not start or advertise the legacy LAN listener, and both owner
surfaces report `Setup required`. Encryption of a payload does not authenticate
the HTTP endpoint or protect pairing metadata, so R2 does not treat same-Wi-Fi
discovery, a six-digit code, or local HTTP as an accepted trust boundary.

The earlier same-Wi-Fi pairing/listener implementation remains in source only
as inactive legacy/prototype evidence. It must not be described as active or
re-enabled until a separately reviewed authenticated TLS, protected relay, or
key-agreement design and physical two-device acceptance pass.

Sync uses append-only event IDs so desktop, laptop and phone can merge rather than overwrite one another. SPOKEN may appear in history. PRIVATE MIND and FACTUAL TRUTH remain encrypted records; private mind is never rendered as ordinary chat or sent to TTS.

Windows can create a password-encrypted `.sarahmind` archive. Optional Google Drive support uploads only that already-encrypted archive to the user's Drive `appDataFolder` after the owner supplies a Google OAuth desktop client file. Gmail is suitable for a security notification, not as Sarah's mind database.

The owner can revoke any paired device. Passwords, payment-card details, booking-site credentials, provider API keys and recovery codes are excluded from sync and memory.

## Desktop + laptop + phone

R2 does not perform automatic or manual network sync with a desktop or laptop.
No inactive peer record, old token, or same-network discovery result authorizes
a connection. The legacy multi-peer and **Sync desktop and laptop now** flows
are preserved but disconnected pending an accepted secure transport.

The encrypted Windows archive contains both the SQLite snapshot and Sarah's device encryption key. Without that key, restored private-mind and factual records would be unreadable. Google Drive receives only the already password-encrypted `.sarahmind` archive. A clean Windows installation can download the newest archive, decrypt it locally with the owner's password, restart Sarah, and then pair a replacement phone.
