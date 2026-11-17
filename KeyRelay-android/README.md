# Beamkey (Android, Java + XML)

Phone app for the password relay. UI follows the Stitch design (Passwords, Pair Laptop, Settings, Add Password,
Laptop Connection Request). Talks to the relay in `room-relay` (Cloudflare Worker, live at https://getbeamkey.com) using the protocol in its `docs/API.md`.

## Open and run
1. Android Studio (Koala or newer) -> Open -> select this folder. Let Gradle sync (needs internet once).
2. Phone needs Android 11+ (API 30) and an enrolled fingerprint (or class-3 face unlock).
3. Server address (default is **https://getbeamkey.com** in both debug and release):
   - Defined in `app/src/main/res/values/strings.xml` (release) and `app/src/debug/res/values/strings.xml` (debug), key `default_server_url`.
   - To test against `npm run dev` on your PC (debug build only, http allowed): in the app open Settings and type
     `http://10.0.2.2:8787` on the emulator, or `http://<your-PC-LAN-IP>:8787` on a real phone. Then press Save.
     Do not leave a local address saved: set it back to `https://getbeamkey.com`.
   - `10.0.2.2` only exists inside the Android emulator. On a real phone it never connects (the app would sit on "Connecting").
   - The store-facing application ID is `com.beamkey.app` (app/build.gradle). The Kotlin/Java package stays `com.keyrelay.app`.
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
