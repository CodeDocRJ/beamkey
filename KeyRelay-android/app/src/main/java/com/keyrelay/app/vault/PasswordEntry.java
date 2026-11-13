package com.keyrelay.app.vault;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Base64;

/** One saved password. The secret exists only as AES-GCM ciphertext (iv + ct). */
public final class PasswordEntry {
    public String id;
    public String name;
    public String username;      // optional, not secret
    public int length;           // shown as "18 characters"
    public boolean requireBio;   // true -> fingerprint-bound key
    public byte[] iv;
    public byte[] ct;
    public long createdAt;

    JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("id", id).put("name", name).put("user", username == null ? "" : username)
                .put("len", length).put("bio", requireBio)
                .put("iv", Base64.getEncoder().encodeToString(iv))
                .put("ct", Base64.getEncoder().encodeToString(ct))
                .put("at", createdAt);
    }

    static PasswordEntry fromJson(JSONObject o) throws JSONException {
        PasswordEntry e = new PasswordEntry();
        e.id = o.getString("id");
        e.name = o.getString("name");
        e.username = o.optString("user", "");
        e.length = o.optInt("len", 0);
        e.requireBio = o.optBoolean("bio", false);
        e.iv = Base64.getDecoder().decode(o.getString("iv"));
        e.ct = Base64.getDecoder().decode(o.getString("ct"));
        e.createdAt = o.optLong("at", 0);
        return e;
    }
}
