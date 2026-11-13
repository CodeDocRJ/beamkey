package com.keyrelay.app.relay;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.keyrelay.app.AppSettings;
import com.keyrelay.app.crypto.KeystoreBox;
import com.keyrelay.app.crypto.ProtocolCrypto;

import org.json.JSONObject;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Owns the room on the relay server and the phone WebSocket. All state is touched on the main thread only;
 * network callbacks are posted back to it. See API.md of the relay project for the wire protocol.
 */
public final class RelayManager {

    public enum Link { OFFLINE, CONNECTING, WAITING, PAIRED }

    public interface Listener {
        void onRelayChanged();
        void onJoinRequest();
        void onJoinCleared();
        void onNotice(String text);
    }

    public interface Result { void done(boolean ok, String message); }

    /** A browser asking to join, waiting for Approve / Deny. */
    public static final class Pending {
        public final String reqId;
        public final String sas;
        public final long at;
        final byte[] browserPub;
        final byte[] aesKey;
        Pending(String reqId, byte[] browserPub, String sas, byte[] aesKey) {
            this.reqId = reqId; this.browserPub = browserPub; this.sas = sas; this.aesKey = aesKey;
            this.at = System.currentTimeMillis();
        }
    }

    public static final long PENDING_MS = 120_000;
    private static final long MIN_REMAINING_MS = 5 * 60_000;
    private static final long BACKGROUND_GRACE_MS = 60_000;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final SharedPreferences sp;
    private final AppSettings settings;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .pingInterval(25, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    private Listener listener;
    private int foregroundCount = 0;

    // room state (persisted, encrypted)
    private String code, token, serverUrl;
    private long expiresAt, ttlMs = 12 * 3600_000L;
    private PrivateKey priv;
    private byte[] pubRaw, browserPub, aesKey;

    // live state
    private WebSocket ws;
    private boolean wsOpen, browserConnected, creating;
    private int gen = 0, backoff = 0;
    private String error;
    private Pending pending, approving;
    private String lastId, lastName, lastKind;
    private long lastAt;

    private final Runnable retryTask = this::ensureRoom;
    private final Runnable backgroundTask = () -> {
        if (foregroundCount == 0) { closeSocket(); changed(); }
    };

    public RelayManager(Context ctx, AppSettings settings) {
        this.sp = ctx.getApplicationContext().getSharedPreferences("relay", Context.MODE_PRIVATE);
        this.settings = settings;
        load();
    }

    // ---------------------------------------------------------------- public API (main thread)

    public void setListener(Listener l) { listener = l; }
    public void clearListener(Listener l) { if (listener == l) listener = null; }

    public Link link() {
        if (wsOpen && code != null) return (aesKey != null && browserConnected) ? Link.PAIRED : Link.WAITING;
        if (creating || ws != null) return Link.CONNECTING;
        return Link.OFFLINE;
    }

    public String code() { return code; }
    public long expiresAt() { return expiresAt; }
    public long ttlMs() { return ttlMs; }
    public String error() { return error; }
    public Pending pending() { return pending; }
    public String lastName() { return lastName; }
    public String lastKind() { return lastKind; }
    public long lastAt() { return lastAt; }
    public String serverUrl() { return code != null && serverUrl != null ? serverUrl : settings.serverUrl(); }
    public String serverAuthority() { return Uri.parse(serverUrl()).getAuthority(); }

    /** Call from every visible screen's onStart. */
    public void onForeground() {
        foregroundCount++;
        main.removeCallbacks(backgroundTask);
        ensureRoom();
    }

    /** Call from onStop. The socket is dropped one minute after the last screen is gone. */
    public void onBackground() {
        if (foregroundCount > 0) foregroundCount--;
        if (foregroundCount == 0) main.postDelayed(backgroundTask, BACKGROUND_GRACE_MS);
    }

    /** Called once per second by the visible screen. */
    public void tick() {
        if (code != null && System.currentTimeMillis() >= expiresAt) {
            closeSocket();
            clearRoomState();
            changed();
            ensureRoom();
        }
        if (pending != null && System.currentTimeMillis() - pending.at > PENDING_MS) clearPending(true);
    }

    /** New pairing code now (also used when the server address changed). Unlocks a locked room too. */
    public void newCode() {
        main.removeCallbacks(retryTask);
        createRoom();
    }

    public void approve() {
        if (pending == null) return;
        approving = pending;
        pending = null;
        sendJson(new JSONObject(), "approve", approving.reqId);
        changed();
    }

    public void deny() {
        if (pending == null) return;
        String id = pending.reqId;
        pending = null;
        sendJson(new JSONObject(), "deny", id);
        changed();
    }

    /** Encrypts and sends. Returns null on success, otherwise a message for the user. */
    public String sendSecret(String entryName, String password) {
        if (link() != Link.PAIRED || ws == null) return "Not paired. Open Pair Laptop and enter the code on the website.";
        try {
            ProtocolCrypto.Encrypted e = ProtocolCrypto.encrypt(aesKey, code, password);
            JSONObject j = new JSONObject().put("t", "send").put("id", e.id).put("iv", e.iv).put("ct", e.ct);
            if (!ws.send(j.toString())) return "Connection lost. Try again in a moment.";
            lastId = e.id; lastName = entryName; lastKind = "sent"; lastAt = System.currentTimeMillis();
            changed();
            return null;
        } catch (IllegalArgumentException e) {
            return "That password is longer than 126 bytes.";
        } catch (Exception e) {
            return "Could not encrypt the password.";
        }
    }

    public void testServer(String url, Result cb) {
        final String base = AppSettings.normalize(url);
        io.execute(() -> {
            boolean ok = false;
            String msg;
            try {
                Request req = new Request.Builder().url(base + "/api/health").build();
                try (Response r = http.newCall(req).execute()) {
                    ok = r.code() == 200;
                    msg = ok ? "Relay reachable." : "Server answered " + r.code() + ". Is this the relay address?";
                }
            } catch (Exception e) {
                msg = "Can't reach the server. " + (base.startsWith("http://")
                        ? "Plain http works only in the debug build." : "Check the address and your network.");
            }
            final boolean fOk = ok; final String fMsg = msg;
            main.post(() -> cb.done(fOk, fMsg));
        });
    }

    // ---------------------------------------------------------------- room lifecycle

    private void ensureRoom() {
        if (foregroundCount <= 0 || creating) return;
        if (code == null) { createRoom(); return; }
        boolean serverChanged = !settings.serverUrl().equals(serverUrl);
        if (serverChanged || expiresAt - System.currentTimeMillis() < MIN_REMAINING_MS) { createRoom(); return; }
        if (ws == null) connect();
    }

    private void createRoom() {
        if (creating) return;
        creating = true;
        error = null;
        closeSocket();
        final String oldCode = code, oldToken = token, oldServer = serverUrl;
        final String server = settings.serverUrl();
        clearRoomState();
        changed();

        io.execute(() -> {
            if (oldCode != null && oldServer != null) deleteRemote(oldServer, oldCode, oldToken);
            try {
                KeyPair kp = ProtocolCrypto.generateKeyPair();
                byte[] pub = ProtocolCrypto.publicRaw(kp.getPublic());
                JSONObject body = new JSONObject().put("phonePub", ProtocolCrypto.b64(pub));
                Request req = new Request.Builder().url(server + "/api/rooms")
                        .post(RequestBody.create(body.toString(), JSON)).build();
                try (Response r = http.newCall(req).execute()) {
                    String text = r.body() != null ? r.body().string() : "";
                    if (r.code() == 201) {
                        JSONObject o = new JSONObject(text);
                        final String c = o.getString("code"), t = o.getString("phoneToken");
                        final long exp = o.getLong("expiresAt");
                        final long ttl = o.optLong("ttlSeconds", 43200) * 1000L;
                        main.post(() -> created(server, c, t, exp, ttl, kp.getPrivate(), pub));
                    } else if (r.code() == 429) {
                        failCreate("Too many new codes in the last hour. Try again later.");
                    } else {
                        failCreate("The server refused the request (" + r.code() + ").");
                    }
                }
            } catch (Exception e) {
                failCreate("Can't reach the relay. Check the address in Settings.");
            }
        });
    }

    private void failCreate(String msg) {
        main.post(() -> {
            creating = false;
            error = msg;
            changed();
            if (foregroundCount > 0) main.postDelayed(retryTask, 15_000);
        });
    }

    private void created(String server, String c, String t, long exp, long ttl, PrivateKey pk, byte[] pub) {
        creating = false;
        error = null;
        serverUrl = server; code = c; token = t; expiresAt = exp; ttlMs = ttl; priv = pk; pubRaw = pub;
        browserPub = null; aesKey = null; browserConnected = false; pending = null; approving = null;
        backoff = 0;
        save();
        changed();
        if (foregroundCount > 0) connect();
    }

    private void deleteRemote(String server, String c, String t) {
        try {
            Request req = new Request.Builder().url(server + "/api/rooms/" + c)
                    .header("Authorization", "Bearer " + t).delete().build();
            http.newCall(req).execute().close();
        } catch (Exception ignored) {}
    }

    private void clearRoomState() {
        code = token = serverUrl = null;
        expiresAt = 0;
        priv = null; pubRaw = browserPub = aesKey = null;
        browserConnected = false;
        pending = approving = null;
        sp.edit().remove("state").apply();
    }

    // ---------------------------------------------------------------- socket

    private void connect() {
        if (code == null || ws != null || foregroundCount <= 0) return;
        final int g = ++gen;
        String url = serverUrl.replaceFirst("^http", "ws") + "/ws/phone/" + code;
        Request req = new Request.Builder().url(url).header("Authorization", "Bearer " + token).build();
        ws = http.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket s, Response r) {
                main.post(() -> { if (g == gen) { wsOpen = true; backoff = 0; error = null; changed(); } });
            }
            @Override public void onMessage(WebSocket s, String text) {
                main.post(() -> { if (g == gen) handle(text); });
            }
            @Override public void onClosing(WebSocket s, int c, String reason) { s.close(1000, null); }
            @Override public void onClosed(WebSocket s, int c, String reason) {
                main.post(() -> { if (g == gen) dropped(0); });
            }
            @Override public void onFailure(WebSocket s, Throwable t, Response r) {
                final int status = r != null ? r.code() : 0;
                main.post(() -> { if (g == gen) dropped(status); });
            }
        });
        changed();
    }

    private void closeSocket() {
        gen++;
        main.removeCallbacks(retryTask);
        if (ws != null) { try { ws.close(1000, "bye"); } catch (Exception ignored) {} }
        ws = null;
        wsOpen = false;
    }

    private void dropped(int httpStatus) {
        ws = null;
        wsOpen = false;
        browserConnected = false;
        if (httpStatus == 401 || httpStatus == 404) {          // room expired or was deleted on the server
            clearRoomState();
            changed();
            ensureRoom();
            return;
        }
        changed();
        if (foregroundCount > 0) {
            long delay = Math.min(1000L << backoff, 30_000L);
            backoff = Math.min(backoff + 1, 5);
            main.postDelayed(retryTask, delay);
        }
    }

    private void sendJson(JSONObject extra, String type, String reqId) {
        if (ws == null) return;
        try {
            extra.put("t", type);
            if (reqId != null) extra.put("reqId", reqId);
            ws.send(extra.toString());
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- incoming messages

    private void handle(String text) {
        try {
            JSONObject m = new JSONObject(text);
            switch (m.optString("t")) {
                case "ready":
                    browserConnected = m.optBoolean("browserConnected");
                    if (!browserConnected && aesKey != null) { aesKey = null; browserPub = null; save(); }
                    changed();
                    break;
                case "join_request":
                    onJoinRequest(m);
                    break;
                case "join_cancelled":
                    clearPending(true);
                    break;
                case "paired":
                    if (approving != null) {
                        browserPub = approving.browserPub;
                        aesKey = approving.aesKey;
                        approving = null;
                        browserConnected = true;
                        save();
                    }
                    changed();
                    break;
                case "delivered": mark(m.optString("id"), "delivered"); break;
                case "copied": mark(m.optString("id"), "copied"); break;
                case "undeliverable":
                    mark(m.optString("id"), "failed");
                    notice("No laptop is connected. Enter the code on the website first.");
                    break;
                case "browser_left":
                    browserConnected = false; aesKey = null; browserPub = null;
                    save();
                    changed();
                    notice("The browser disconnected. Enter the code on the website again.");
                    break;
                case "denied_ok":
                    if (m.optBoolean("locked")) notice("Room locked after 3 denials. Tap New Code to start over.");
                    break;
                case "closed":
                    closeSocket();
                    clearRoomState();
                    changed();
                    ensureRoom();
                    break;
                case "error":
                    String r = m.optString("reason");
                    if ("rate_limited".equals(r)) notice("Too many sends. Wait a moment.");
                    else if ("bad_send".equals(r)) notice("The server rejected that message.");
                    break;
                default: break;
            }
        } catch (Exception ignored) {}
    }

    private void onJoinRequest(JSONObject m) {
        try {
            byte[] bpub = ProtocolCrypto.unb64(m.getString("browserPub"));
            ProtocolCrypto.Session s = ProtocolCrypto.derive(priv, bpub, pubRaw, bpub);
            pending = new Pending(m.getString("reqId"), bpub, s.sas, s.aesKey);
            changed();
            if (listener != null) listener.onJoinRequest();
        } catch (Exception e) {
            sendJson(new JSONObject(), "deny", m.optString("reqId"));
        }
    }

    private void clearPending(boolean tellUi) {
        if (pending == null) return;
        pending = null;
        changed();
        if (tellUi && listener != null) listener.onJoinCleared();
    }

    private void mark(String id, String kind) {
        if (id.equals(lastId)) { lastKind = kind; lastAt = System.currentTimeMillis(); changed(); }
    }

    private void notice(String text) { if (listener != null) listener.onNotice(text); }
    private void changed() { if (listener != null) listener.onRelayChanged(); }

    // ---------------------------------------------------------------- persistence (Keystore-encrypted)

    private void save() {
        if (code == null) { sp.edit().remove("state").apply(); return; }
        try {
            JSONObject o = new JSONObject()
                    .put("code", code).put("token", token).put("server", serverUrl)
                    .put("exp", expiresAt).put("ttl", ttlMs)
                    .put("priv", Base64.getEncoder().encodeToString(priv.getEncoded()))
                    .put("pub", Base64.getEncoder().encodeToString(pubRaw));
            if (browserPub != null && aesKey != null) {
                o.put("bpub", Base64.getEncoder().encodeToString(browserPub))
                 .put("aes", Base64.getEncoder().encodeToString(aesKey));
            }
            byte[] sealed = KeystoreBox.seal(o.toString().getBytes("UTF-8"));
            sp.edit().putString("state", Base64.getEncoder().encodeToString(sealed)).apply();
        } catch (Exception ignored) {}
    }

    private void load() {
        String s = sp.getString("state", null);
        if (s == null) return;
        try {
            JSONObject o = new JSONObject(new String(KeystoreBox.open(Base64.getDecoder().decode(s)), "UTF-8"));
            long exp = o.getLong("exp");
            if (exp <= System.currentTimeMillis()) { sp.edit().remove("state").apply(); return; }
            code = o.getString("code"); token = o.getString("token"); serverUrl = o.getString("server");
            expiresAt = exp; ttlMs = o.optLong("ttl", ttlMs);
            priv = KeyFactory.getInstance("EC").generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(o.getString("priv"))));
            pubRaw = Base64.getDecoder().decode(o.getString("pub"));
            if (o.has("bpub") && o.has("aes")) {
                browserPub = Base64.getDecoder().decode(o.getString("bpub"));
                aesKey = Base64.getDecoder().decode(o.getString("aes"));
            }
        } catch (Exception e) {
            code = null;
            sp.edit().remove("state").apply();
        }
    }
}
