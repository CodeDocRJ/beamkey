package com.keyrelay.app;

import android.app.Application;

import com.keyrelay.app.relay.RelayManager;
import com.keyrelay.app.vault.Vault;

/** Process-wide singletons: the relay connection must outlive individual screens. */
public class KeyRelayApp extends Application {

    private AppSettings settings;
    private Vault vault;
    private RelayManager relay;

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new AppSettings(this);
        vault = new Vault(this);
        relay = new RelayManager(this, settings);
    }

    public AppSettings settings() { return settings; }
    public Vault vault() { return vault; }
    public RelayManager relay() { return relay; }
}
