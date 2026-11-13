# KeyRelay (Android, Java + XML)

Phone app for the password relay. UI follows the Stitch design (Passwords, Pair Laptop, Settings, Add Password,
Laptop Connection Request). Talks to the relay in `room-relay` (Cloudflare Worker) using the protocol in its `docs/API.md`.

## Open and run
1. Android Studio (Koala or newer) -> Open -> select this folder. Let Gradle sync (needs internet once).
2. Phone needs Android 11+ (API 30) and an enrolled fingerprint (or class-3 face unlock).
3. Server address:
   - **Debug build** (cleartext allowed): `app/src/debug/res/values/strings.xml` -> `http://10.0.2.2:8787` for the emulator.
     On a real phone open Settings in the app and type `http://<your-PC-LAN-IP>:8787` while `npm run dev` runs.
   - **Release build** (HTTPS only): set `default_server_url` in `app/src/main/res/values/strings.xml`,
     or change it in the app under Settings.
4. First run: tap Add, save a password (fingerprint prompt appears), open **Pair Laptop**, enter the shown code on the website,
   compare the check code, tap Approve, then tap **Send to Laptop**. On the laptop click once in the relay window, then Ctrl+V.

## Code map
| File | Job |
|---|---|
| `relay/RelayManager.java` | Room lifecycle (POST /api/rooms, DELETE), phone WebSocket, reconnect with back-off, join requests, encrypted send, session persistence (Keystore-encrypted) |
| `crypto/ProtocolCrypto.java` | ECDH P-256, HKDF, check code, AES-GCM (wire-compatible with the website) |
| `vault/Vault.java` | Saved passwords as ciphertext; Keystore AES key per use, optional fingerprint-bound key |
| `ui/MainActivity.java` | Passwords / Pair / Settings pages, send flow |
| `ui/AddPasswordActivity.java` | Add or replace a password, generator, strength meter |
| `ui/ApprovalActivity.java` | Compare check code, Approve / Deny |

## Behaviour to know
- A new pairing code is created on first open, then again when under 5 minutes remain at app start or when it expires (12 h).
- The socket closes 1 minute after you leave the app and reconnects when you return; the paired browser stays paired
  as long as its tab is open.
- If fingerprints are added or removed, Android permanently locks fingerprint-protected passwords; delete and re-add them.
- Screens block screenshots (FLAG_SECURE).
