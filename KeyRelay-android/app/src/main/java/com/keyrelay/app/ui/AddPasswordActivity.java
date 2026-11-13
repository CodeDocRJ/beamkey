package com.keyrelay.app.ui;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.keyrelay.app.KeyRelayApp;
import com.keyrelay.app.R;
import com.keyrelay.app.vault.PasswordEntry;
import com.keyrelay.app.vault.Vault;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.UUID;

import javax.crypto.Cipher;

/** Add a new password, or replace the secret of an existing one (EXTRA_ID). */
public class AddPasswordActivity extends Activity {

    static final String EXTRA_ID = "id";
    static final int MAX_BYTES = 126;

    private Vault vault;
    private PasswordEntry editing;
    private EditText etName, etUser, etPassword;
    private Switch swBio;
    private ImageButton btnEye;
    private TextView tvStrength;
    private final View[] segs = new View[4];
    private boolean visible = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_add_password);
        vault = ((KeyRelayApp) getApplication()).vault();

        etName = findViewById(R.id.etName);
        etUser = findViewById(R.id.etUser);
        etPassword = findViewById(R.id.etPassword);
        swBio = findViewById(R.id.swBio);
        btnEye = findViewById(R.id.btnEye);
        tvStrength = findViewById(R.id.tvStrength);
        segs[0] = findViewById(R.id.seg1); segs[1] = findViewById(R.id.seg2);
        segs[2] = findViewById(R.id.seg3); segs[3] = findViewById(R.id.seg4);

        String id = getIntent().getStringExtra(EXTRA_ID);
        if (id != null) editing = vault.find(id);
        if (editing != null) {
            ((TextView) findViewById(R.id.tvBarTitle)).setText("Replace Password");
            ((TextView) findViewById(R.id.tvHeadline)).setText("Replace Password");
            ((TextView) findViewById(R.id.tvSub)).setText("Enter the new password. It overwrites the saved one.");
            etName.setText(editing.name);
            etUser.setText(editing.username);
            swBio.setChecked(editing.requireBio);
        }
        if (!Vault.biometricAvailable(this)) {
            swBio.setChecked(false);
            swBio.setEnabled(false);
            ((TextView) findViewById(R.id.tvBioHint)).setText(
                    "No fingerprint is enrolled on this phone. Add one in Android settings to use this.");
        }

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnCancel).setOnClickListener(v -> finish());
        findViewById(R.id.btnSave).setOnClickListener(v -> save());
        findViewById(R.id.btnGenerate).setOnClickListener(v -> {
            etPassword.setText(generate());
            showPassword(true);
        });
        btnEye.setOnClickListener(v -> showPassword(!visible));
        etPassword.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void afterTextChanged(Editable s) { strength(s.toString()); }
        });
        strength("");
    }

    private void showPassword(boolean show) {
        visible = show;
        int sel = etPassword.getSelectionEnd();
        etPassword.setInputType(InputType.TYPE_CLASS_TEXT | (show
                ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
        etPassword.setTypeface(getResources().getFont(R.font.jetbrains_mono));
        etPassword.setSelection(Math.max(0, Math.min(sel, etPassword.length())));
        btnEye.setImageResource(show ? R.drawable.ic_visibility_off : R.drawable.ic_visibility);
    }

    private void strength(String pw) {
        boolean lo = false, up = false, di = false, sy = false;
        for (char c : pw.toCharArray()) {
            if (Character.isLowerCase(c)) lo = true;
            else if (Character.isUpperCase(c)) up = true;
            else if (Character.isDigit(c)) di = true;
            else sy = true;
        }
        int classes = (lo ? 1 : 0) + (up ? 1 : 0) + (di ? 1 : 0) + (sy ? 1 : 0);
        int len = pw.length(), score = 0;
        if (len > 0) score = 1;
        if (len >= 10 && classes >= 2) score = 2;
        if (len >= 12 && classes >= 3) score = 3;
        if (len >= 16 && classes == 4) score = 4;
        String[] names = {"Awaiting input", "Weak", "Fair", "Good", "Strong"};
        tvStrength.setText(names[score]);
        int on = score == 1 ? getColor(R.color.error) : score == 2 ? getColor(R.color.warning) : getColor(R.color.secondary);
        for (int i = 0; i < 4; i++) segs[i].setBackgroundColor(i < score ? on : 0xFF2C2F37);
    }

    private static String generate() {
        final String lower = "abcdefghijkmnopqrstuvwxyz", upper = "ABCDEFGHJKLMNPQRSTUVWXYZ",
                digits = "23456789", sym = "!@#$%^&*-_=+?";
        final String all = lower + upper + digits + sym;
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (String set : new String[]{lower, upper, digits, sym})
            for (int i = 0; i < 2; i++) sb.append(set.charAt(r.nextInt(set.length())));
        while (sb.length() < 20) sb.append(all.charAt(r.nextInt(all.length())));
        char[] a = sb.toString().toCharArray();
        for (int i = a.length - 1; i > 0; i--) { int j = r.nextInt(i + 1); char t = a[i]; a[i] = a[j]; a[j] = t; }
        return new String(a);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private void save() {
        final String name = etName.getText().toString().trim();
        final String user = etUser.getText().toString().trim();
        final String pw = etPassword.getText().toString();
        if (name.isEmpty()) { toast("Give this password a name."); return; }
        if (pw.isEmpty()) { toast("Enter the password."); return; }
        if (pw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) { toast("Passwords can be at most 126 bytes long."); return; }
        if (editing == null && vault.all().size() >= 10) { toast("You can save up to 10 passwords."); return; }

        final boolean bio = swBio.isChecked();
        try {
            Cipher c = vault.encryptCipher(bio);
            if (bio) {
                Auth.prompt(this, "Lock " + name, "Confirm it's you to protect this password", c, new Auth.Done() {
                    @Override public void ok(Cipher authorised) { store(authorised, name, user, pw, true); }
                    @Override public void fail(String message) { if (message != null) toast(message); }
                });
            } else {
                store(c, name, user, pw, false);
            }
        } catch (GeneralSecurityException e) {
            toast("Could not open the secure vault.");
        }
    }

    private void store(Cipher c, String name, String user, String pw, boolean bio) {
        try {
            PasswordEntry e = new PasswordEntry();
            e.id = editing != null ? editing.id : UUID.randomUUID().toString();
            e.createdAt = editing != null ? editing.createdAt : System.currentTimeMillis();
            e.name = name;
            e.username = user;
            e.length = pw.length();
            e.requireBio = bio;
            e.ct = c.doFinal(pw.getBytes(StandardCharsets.UTF_8));
            e.iv = c.getIV();
            vault.save(e);
            setResult(RESULT_OK);
            finish();
        } catch (GeneralSecurityException ex) {
            toast("Could not encrypt the password.");
        }
    }
}
