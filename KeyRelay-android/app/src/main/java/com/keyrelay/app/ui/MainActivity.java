package com.keyrelay.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.keyrelay.app.AppSettings;
import com.keyrelay.app.KeyRelayApp;
import com.keyrelay.app.R;
import com.keyrelay.app.relay.RelayManager;
import com.keyrelay.app.relay.RelayManager.Link;
import com.keyrelay.app.vault.PasswordEntry;
import com.keyrelay.app.vault.Vault;

import java.security.GeneralSecurityException;
import java.util.Date;
import java.util.List;

import javax.crypto.Cipher;

/** Passwords / Pair Laptop / Settings, matching the Stitch design. */
public class MainActivity extends Activity implements RelayManager.Listener {

    private static final int MAX_PASSWORDS = 10;
    private static final int[] ICONS = {R.drawable.ic_badge, R.drawable.ic_apartment, R.drawable.ic_key};

    private RelayManager relay;
    private Vault vault;
    private AppSettings settings;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            relay.tick();
            renderTimers();
            ui.postDelayed(this, 1000);
        }
    };

    private int page = 0;
    private View pagePasswords, pagePair, pageSettings;
    private View headerDot, dotLink, dotPair, rowLast;
    private TextView headerStatus, tvLinkTitle, tvLinkSub, tvLast, tvLastAge, tvEmpty;
    private TextView tvPairStatus, tvNode, tvUrl, tvCode, tvExpiry, tvStep2, tvServerResult, tvVersion;
    private ImageView ivLast;
    private ProgressBar pbExpiry;
    private LinearLayout listPasswords;
    private Button btnAdd, btnAddAnother;
    private EditText etServer;
    private final ImageView[] navIcon = new ImageView[3];
    private final TextView[] navLabel = new TextView[3];

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_main);
        KeyRelayApp app = (KeyRelayApp) getApplication();
        relay = app.relay();
        vault = app.vault();
        settings = app.settings();

        pagePasswords = findViewById(R.id.pagePasswords);
        pagePair = findViewById(R.id.pagePair);
        pageSettings = findViewById(R.id.pageSettings);
        headerDot = findViewById(R.id.headerDot);
        headerStatus = findViewById(R.id.headerStatus);
        dotLink = findViewById(R.id.dotLink);
        dotPair = findViewById(R.id.dotPair);
        rowLast = findViewById(R.id.rowLast);
        tvLinkTitle = findViewById(R.id.tvLinkTitle);
        tvLinkSub = findViewById(R.id.tvLinkSub);
        tvLast = findViewById(R.id.tvLast);
        tvLastAge = findViewById(R.id.tvLastAge);
        ivLast = findViewById(R.id.ivLast);
        tvEmpty = findViewById(R.id.tvEmpty);
        listPasswords = findViewById(R.id.listPasswords);
        btnAdd = findViewById(R.id.btnAdd);
        btnAddAnother = findViewById(R.id.btnAddAnother);
        tvPairStatus = findViewById(R.id.tvPairStatus);
        tvNode = findViewById(R.id.tvNode);
        tvUrl = findViewById(R.id.tvUrl);
        tvCode = findViewById(R.id.tvCode);
        tvExpiry = findViewById(R.id.tvExpiry);
        tvStep2 = findViewById(R.id.tvStep2);
        pbExpiry = findViewById(R.id.pbExpiry);
        etServer = findViewById(R.id.etServer);
        tvServerResult = findViewById(R.id.tvServerResult);
        tvVersion = findViewById(R.id.tvVersion);
        navIcon[0] = findViewById(R.id.navPasswordsIcon); navLabel[0] = findViewById(R.id.navPasswordsLabel);
        navIcon[1] = findViewById(R.id.navPairIcon);      navLabel[1] = findViewById(R.id.navPairLabel);
        navIcon[2] = findViewById(R.id.navSettingsIcon);  navLabel[2] = findViewById(R.id.navSettingsLabel);

        findViewById(R.id.navPasswords).setOnClickListener(v -> showPage(0));
        findViewById(R.id.navPair).setOnClickListener(v -> showPage(1));
        findViewById(R.id.navSettings).setOnClickListener(v -> showPage(2));
        findViewById(R.id.btnProfile).setOnClickListener(v -> showPage(2));
        btnAdd.setOnClickListener(v -> startActivity(new Intent(this, AddPasswordActivity.class)));
        btnAddAnother.setOnClickListener(v -> startActivity(new Intent(this, AddPasswordActivity.class)));

        findViewById(R.id.btnCopyCode).setOnClickListener(v -> copyCode());
        findViewById(R.id.btnNewCode).setOnClickListener(v -> {
            relay.newCode();
            Toast.makeText(this, "Creating a new code…", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btnSaveServer).setOnClickListener(v -> saveServer());
        findViewById(R.id.btnTestServer).setOnClickListener(v -> testServer());
        findViewById(R.id.btnErase).setOnClickListener(v -> confirmErase());

        etServer.setText(settings.serverUrl());
        try {
            tvVersion.setText("KeyRelay " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception ignored) {}
        showPage(0);
    }

    @Override
    protected void onStart() {
        super.onStart();
        relay.setListener(this);
        relay.onForeground();
        render();
        ui.post(tick);
        if (relay.pending() != null) openApproval();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderList();
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacks(tick);
        relay.clearListener(this);
        relay.onBackground();
    }

    // ------------------------------------------------------------------ RelayManager.Listener

    @Override public void onRelayChanged() { render(); }
    @Override public void onJoinRequest() { openApproval(); }
    @Override public void onJoinCleared() {}
    @Override public void onNotice(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }

    private void openApproval() { startActivity(new Intent(this, ApprovalActivity.class)); }

    // ------------------------------------------------------------------ navigation

    private void showPage(int p) {
        page = p;
        pagePasswords.setVisibility(p == 0 ? View.VISIBLE : View.GONE);
        pagePair.setVisibility(p == 1 ? View.VISIBLE : View.GONE);
        pageSettings.setVisibility(p == 2 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < 3; i++) {
            int c = getColor(i == p ? R.color.secondary : R.color.outline);
            navIcon[i].setImageTintList(ColorStateList.valueOf(c));
            navLabel[i].setTextColor(c);
        }
    }

    // ------------------------------------------------------------------ rendering

    private void render() {
        Link l = relay.link();
        int dot;
        String head;
        switch (l) {
            case PAIRED:     dot = R.drawable.bg_dot_green; head = "LINKED"; break;
            case WAITING:    dot = R.drawable.bg_dot_amber; head = "WAITING FOR LAPTOP"; break;
            case CONNECTING: dot = R.drawable.bg_dot_amber; head = "CONNECTING"; break;
            default:         dot = R.drawable.bg_dot_grey;  head = "OFFLINE"; break;
        }
        headerDot.setBackgroundResource(dot);
        headerStatus.setText(head);

        // Passwords page: link card
        dotLink.setBackgroundResource(dot);
        String err = relay.error();
        switch (l) {
            case PAIRED:
                tvLinkTitle.setText("Connected to laptop");
                tvLinkSub.setText("Browser paired · end-to-end encrypted");
                break;
            case WAITING:
                tvLinkTitle.setText("Waiting for laptop");
                tvLinkSub.setText("Enter your pairing code on the website");
                break;
            case CONNECTING:
                tvLinkTitle.setText("Connecting to relay…");
                tvLinkSub.setText(relay.serverAuthority());
                break;
            default:
                tvLinkTitle.setText("Not connected");
                tvLinkSub.setText(err != null ? err : "Opening the relay connection…");
        }
        renderLast();

        // Pair page
        dotPair.setBackgroundResource(dot);
        tvPairStatus.setText(err != null ? err : l == Link.PAIRED ? "Laptop connected"
                : l == Link.WAITING ? "Waiting for laptop to connect…"
                : l == Link.CONNECTING ? "Connecting to relay…" : "Offline");
        tvNode.setText(relay.serverAuthority());
        tvUrl.setText(relay.serverUrl());
        String c = relay.code();
        tvCode.setText(c == null ? "----  ----" : c.substring(0, 4) + "-" + c.substring(4));
        tvStep2.setText(c == null ? "Enter the 8-character code shown above into the box on the page."
                : "Enter " + c.substring(0, 4) + "-" + c.substring(4) + " into the box on the page.");
        renderTimers();
        renderList();
    }

    private void renderLast() {
        String name = relay.lastName();
        if (name == null) { rowLast.setVisibility(View.GONE); return; }
        rowLast.setVisibility(View.VISIBLE);
        String kind = relay.lastKind();
        String status; int color;
        switch (kind == null ? "" : kind) {
            case "copied":    status = "copied on laptop"; color = R.color.secondary; break;
            case "delivered": status = "delivered";        color = R.color.warning;   break;
            case "failed":    status = "not delivered";    color = R.color.error;     break;
            default:          status = "sent";             color = R.color.warning;
        }
        tvLast.setText("Last sent: " + name + " (" + status + ")");
        ivLast.setImageTintList(ColorStateList.valueOf(getColor(color)));
    }

    private void renderTimers() {
        long now = System.currentTimeMillis();
        if (relay.code() == null) {
            tvExpiry.setText(relay.error() != null ? "No active code" : "Creating code…");
            pbExpiry.setProgress(0);
        } else {
            long rem = Math.max(0, relay.expiresAt() - now);
            String clock = DateFormat.getTimeFormat(this).format(new Date(relay.expiresAt()));
            long mins = rem / 60000;
            tvExpiry.setText("Expires at " + clock + " · in " + (mins >= 60 ? (mins / 60) + "h " + (mins % 60) + "m" : mins + " min"));
            pbExpiry.setProgress((int) (rem * 1000 / Math.max(1, relay.ttlMs())));
        }
        if (relay.lastName() != null) {
            long s = Math.max(0, (now - relay.lastAt()) / 1000);
            tvLastAge.setText(s < 10 ? "Just now" : s < 60 ? s + "s ago" : (s / 60) + " min ago");
        }
    }

    private void renderList() {
        List<PasswordEntry> items = vault.all();
        listPasswords.removeAllViews();
        boolean paired = relay.link() == Link.PAIRED;
        LayoutInflater inf = getLayoutInflater();
        for (int i = 0; i < items.size(); i++) {
            final PasswordEntry e = items.get(i);
            View v = inf.inflate(R.layout.item_password, listPasswords, false);
            ((ImageView) v.findViewById(R.id.ivIcon)).setImageResource(ICONS[i % ICONS.length]);
            ((TextView) v.findViewById(R.id.tvName)).setText(e.name);
            String meta = e.length + " characters" + (e.username != null && !e.username.isEmpty() ? " · " + e.username : "");
            ((TextView) v.findViewById(R.id.tvMeta)).setText(meta);
            StringBuilder mask = new StringBuilder();
            for (int k = 0; k < Math.min(e.length, 24); k++) mask.append('•');
            ((TextView) v.findViewById(R.id.tvMask)).setText(mask);

            Button send = v.findViewById(R.id.btnSend);
            send.setAlpha(paired ? 1f : 0.55f);
            send.setOnClickListener(x -> send(e));
            v.findViewById(R.id.btnMore).setOnClickListener(x -> {
                PopupMenu pm = new PopupMenu(this, x);
                pm.getMenu().add(0, 1, 0, "Replace password");
                pm.getMenu().add(0, 2, 1, "Delete");
                pm.setOnMenuItemClickListener(it -> {
                    if (it.getItemId() == 1) {
                        startActivity(new Intent(this, AddPasswordActivity.class).putExtra(AddPasswordActivity.EXTRA_ID, e.id));
                    } else {
                        confirmDelete(e);
                    }
                    return true;
                });
                pm.show();
            });
            listPasswords.addView(v);
        }
        boolean empty = items.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        btnAddAnother.setVisibility(!empty && items.size() < MAX_PASSWORDS ? View.VISIBLE : View.GONE);
        btnAdd.setVisibility(items.size() < MAX_PASSWORDS ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ actions

    private void send(PasswordEntry e) {
        if (relay.link() != Link.PAIRED) {
            Toast.makeText(this, relay.link() == Link.WAITING
                    ? "Enter the pairing code on the website first."
                    : "Not connected to the relay yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Cipher c = vault.decryptCipher(e);
            if (e.requireBio) {
                Auth.prompt(this, "Send " + e.name, "Confirm it's you to send this password", c, new Auth.Done() {
                    @Override public void ok(Cipher authorised) { decryptAndSend(authorised, e); }
                    @Override public void fail(String message) {
                        if (message != null) Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
            } else {
                decryptAndSend(c, e);
            }
        } catch (KeyPermanentlyInvalidatedException ex) {
            Toast.makeText(this, "Your fingerprints changed, so Android locked this password for good. Delete it and add it again.",
                    Toast.LENGTH_LONG).show();
        } catch (GeneralSecurityException ex) {
            Toast.makeText(this, "Could not open the secure vault.", Toast.LENGTH_LONG).show();
        }
    }

    private void decryptAndSend(Cipher c, PasswordEntry e) {
        try {
            String pw = Vault.decrypt(c, e);
            String err = relay.sendSecret(e.name, pw);
            if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show();
            else Toast.makeText(this, "Sent. Click in the laptop window to copy.", Toast.LENGTH_SHORT).show();
        } catch (GeneralSecurityException ex) {
            Toast.makeText(this, "Could not decrypt this password.", Toast.LENGTH_LONG).show();
        }
    }

    private void confirmDelete(PasswordEntry e) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Delete " + e.name + "?")
                .setMessage("This removes the saved password from this phone.")
                .setPositiveButton("Delete", (d, w) -> { vault.delete(e.id); renderList(); })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmErase() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Erase all saved passwords?")
                .setMessage("Every password and the vault keys are deleted from this phone. This can't be undone.")
                .setPositiveButton("Erase", (d, w) -> { vault.eraseAll(); renderList(); })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void copyCode() {
        String c = relay.code();
        if (c == null) { Toast.makeText(this, "No code yet.", Toast.LENGTH_SHORT).show(); return; }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Pairing code", c);
        ClipDescription d = clip.getDescription();
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        d.setExtras(extras);
        cm.setPrimaryClip(clip);
        Toast.makeText(this, "Code copied", Toast.LENGTH_SHORT).show();
    }

    private void saveServer() {
        String u = AppSettings.normalize(etServer.getText().toString());
        if (!(u.startsWith("https://") || u.startsWith("http://")) || u.length() < 9) {
            tvServerResult.setTextColor(getColor(R.color.error));
            tvServerResult.setText("Enter the full address, for example https://relay.example.com");
            return;
        }
        etServer.setText(u);
        boolean changed = !u.equals(settings.serverUrl());
        settings.setServerUrl(u);
        tvServerResult.setTextColor(getColor(R.color.on_surface_variant));
        tvServerResult.setText(changed ? "Saved. Creating a new pairing code on this server…" : "Saved.");
        if (changed) relay.newCode();
    }

    private void testServer() {
        String u = AppSettings.normalize(etServer.getText().toString());
        tvServerResult.setTextColor(getColor(R.color.on_surface_variant));
        tvServerResult.setText("Testing…");
        relay.testServer(u, (ok, msg) -> {
            tvServerResult.setTextColor(getColor(ok ? R.color.secondary : R.color.error));
            tvServerResult.setText(msg);
        });
    }
}
