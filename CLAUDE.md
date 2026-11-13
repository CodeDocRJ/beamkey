# KeyRelay workspace

Send long work passwords from an Android phone to a locked-down Windows laptop without typing them.
Flow: phone app (biometric) -> relay server (end-to-end encrypted) -> website open on the laptop -> text lands on the Windows clipboard.

## Layout
- `room-relay/`    Cloudflare Worker + Durable Objects + static website (Node project). Edited in VS Code.
- `KeyRelay-android/`  Android app, Java + XML, package `com.keyrelay.app`. Edited in Android Studio.
- Claude Code may be run from this root and edit both. Keep changes to each project inside its own folder.

## Hard rules
- `room-relay/public/proto.js` (browser/Node crypto) and `KeyRelay-android/app/src/main/java/com/keyrelay/app/crypto/ProtocolCrypto.java` MUST stay byte-compatible. Any change to one needs the matching change in the other, then re-run `npm run vectors` and compare with `room-relay/docs/test-vectors.json`.
- The relay never stores messages and never sees plaintext. Do not add logging of message bodies, passwords, or public keys.
- The laptop is a corporate machine: no installs, no admin rights. The website must work in a plain browser tab. Clipboard writes need a user gesture, so the page arms a click/keypress/focus listener; do not try to bypass that.
- Android: min SDK 30, FLAG_SECURE on sensitive screens, Keystore keys with biometric auth, no plaintext password storage.

## Protocol summary (full spec: `room-relay/docs/API.md`)
- Phone creates a room: `POST /api/rooms` with its public key -> 8-char code (Crockford base32, no I/L/O/U) + phoneToken. Room lives 12h (Durable Object alarm).
- Phone WebSocket `/ws/phone/{code}` (Bearer token); browser WebSocket `/ws/browser/{code}`.
- Browser sends join_request; phone shows an 8-char check code (SAS) and Approve/Deny. 3 denials lock the room.
- Crypto: ephemeral ECDH P-256 (65-byte uncompressed keys, base64url), HKDF-SHA256 (salt = SHA256(phonePub||browserPub), info "rcvpass-v1 aes-gcm"), AES-256-GCM, AAD = code + "|" + id. Plaintext padded to 128 bytes: [len_hi][len_lo][password][zeros], max 126 bytes.
- Rate limits: 10 room creates/hour/IP, 20 browser joins/10 min/IP, 20 sends/min/room.

## Commands
Relay (run from `room-relay/`, Node 20+):
- `npm install`           once
- `npm run dev`           local server at http://localhost:8787
- `npx wrangler dev --ip 0.0.0.0`   reachable from a real phone on the same Wi-Fi
- `npm test`              end-to-end checks
- `npm run sim`           fake phone for testing the website without the app
- `npm run vectors`       regenerate crypto test vectors
- `npx wrangler deploy`   publish to Cloudflare

Android (Android Studio, open `KeyRelay-android/`): Run the `app` debug config.
- Emulator reaches the PC server at `http://10.0.2.2:8787` (debug default).
- Real phone: Settings tab in the app -> server URL `http://<PC-LAN-IP>:8787`.
- Release builds require https; set the real URL in `res/values/strings.xml` (`default_server_url`).

## Status / known gaps
- Android app was compile-checked but not yet run on a device or emulator.
- Website not yet tested in a real browser; relay not yet deployed to Cloudflare.
- Planned: custom domain, neutral product name, hybrid/iOS app later.
- Test the deployed site on workers.dev from the work laptop before buying a domain (company filters may block it).
