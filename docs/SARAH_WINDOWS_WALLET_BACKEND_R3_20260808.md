# Sarah Windows wallet backend — R3

Status: bounded backend and source tests implemented; owner UI and physical
Windows acceptance are pending.

`windows-companion/sarah_wallet.py` provides profile-isolated loyalty records
and owner-selected ticket/pass images. The vault is not a purchase system and
never interprets an uploaded pass as proof of payment, admission, validity, or
availability.

## Storage and privacy boundary

- A random AES-256-GCM wallet data key encrypts every complete record.
- On Windows that key is wrapped using the existing current-Windows-user
  DPAPI protection path. Source tests on other platforms use the existing
  local-test fallback.
- Record payloads include the exact Sarah profile ID, loyalty metadata,
  ticket/pass metadata, exact supplied HTTPS official URL, and optional
  sanitized image.
- Plaintext member identifiers, profile IDs, image bytes, and metadata are not
  present in the vault file.
- Image metadata and absolute source paths are removed. The retained image is
  a bounded PNG so QR/barcode edges are not intentionally made lossy.
- The existing sync allowlist does not include this wallet. The module's
  future-facing `sync_projection()` excludes image bytes, member identifiers,
  original filenames, and arbitrary notes. It is not connected to sync.

## Limits

- 100 records per profile;
- 400 records per device;
- 12 MiB maximum selected source image;
- 20 million decoded pixels;
- 2,048-pixel maximum retained edge;
- 4 MiB maximum sanitized PNG;
- 64 MiB maximum encrypted vault;
- bounded field and URL lengths.

Passwords, passcodes, PINs, CVVs, API/private keys, bank/routing details, and
Luhn-valid payment-card numbers are rejected. Official links must be exact
HTTPS URLs with no embedded credentials or local/private host.

## Explicit operations

- `add_loyalty(...)`
- `add_ticket_pass(...)`
- `list_records(...)`
- `get_image_bytes(...)` for in-memory owner display
- `remove_record(...)`
- `storage_status()`

Removal affects only the exact active-profile record and returns a deletion
receipt. It cannot remove another profile's record. Corrupt or unauthenticated
vault/key data fails closed and is not overwritten.

## Minimal Workbench UI integration (not performed in this backend task)

Import only:

```python
from sarah_wallet import SarahWallet, WalletError, WalletValidationError
```

After the existing `SarahDatabase` is constructed:

```python
self.wallet = SarahWallet(self.db)
```

The Workbench needs three owner actions and one viewer:

1. **Add loyalty card** → collect the bounded fields, optional official HTTPS
   URL, and optional owner-selected card/QR image; call `add_loyalty`.
2. **Add ticket or pass** → use a native file picker for one owner-selected
   image, collect ticket metadata and exact official HTTPS URL, then call
   `add_ticket_pass`.
3. **Remove selected item** → show the exact title/type and require owner
   confirmation before `remove_record`.
4. **Wallet list/view** → call `list_records`; call `get_image_bytes` only for
   the selected item and render those bytes in memory. Do not write a decrypted
   image to a shared/temp directory.

Catch `WalletValidationError` for owner-correctable input and `WalletError`
for a fail-closed storage message. Do not add a Buy button, claim a purchase,
open a URL automatically, put passwords/payment details in the wallet, or add
the encrypted vault/image bytes to device sync.

## Verification

`windows-companion/tests/test_sarah_wallet.py` covers encryption at rest,
profile isolation, image sanitization, exact HTTPS URLs, secret/payment
rejection, record/image/vault limits, explicit deletion, corruption handling,
and the no-image/no-member-ID sync projection.

Physical owner review must still validate QR/barcode readability from a real
saved pass, Windows DPAPI behavior in the packaged EXE, Workbench layout, URL
opening only after an owner click, and delete confirmation. No judge-ready or
owner-accepted wallet claim is made yet.
