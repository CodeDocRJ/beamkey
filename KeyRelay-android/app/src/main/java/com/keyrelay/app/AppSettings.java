package com.keyrelay.app;

import android.content.Context;
import android.content.SharedPreferences;

/** User-editable settings (the relay server address). */
public final class AppSettings {

    private final Context app;
    private final SharedPreferences sp;

    public AppSettings(Context ctx) {
        app = ctx.getApplicationContext();
        sp = app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** Normalised base URL without a trailing slash, e.g. https://relay.example.com */
    public String serverUrl() {
        String v = sp.getString("server_url", null);
        if (v == null || v.trim().isEmpty()) v = app.getString(R.string.default_server_url);
        return normalize(v);
    }

    public void setServerUrl(String url) {
        sp.edit().putString("server_url", normalize(url)).apply();
    }

    public static String normalize(String raw) {
        String v = raw == null ? "" : raw.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        if (!v.isEmpty() && !v.startsWith("http://") && !v.startsWith("https://")) v = "https://" + v;
        return v;
    }
}
