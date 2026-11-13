package com.keyrelay.app.vault;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.biometrics.BiometricManager;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;

import org.json.JSONArray;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Local password vault.
 *  - Metadata (name, length) lives in SharedPreferences; the secret is only ever stored as ciphertext.
 *  - Two Keystore AES-256-GCM keys: one without auth, one bound to STRONG biometrics that needs a
 *    fingerprint for every single use (encrypt and decrypt), enforced through BiometricPrompt + CryptoObject.
 *  - Android permanently invalidates the biometric key when fingerprints are added or removed.
 */
public final class Vault {

    private static final String ALIAS_BIO = "kr_vault_bio";
    private static final String ALIAS_PLAIN = "kr_vault_plain";
    private static final String TRANSFORM = "AES/GCM/NoPadding";

    private final SharedPreferences sp;

    public Vault(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences("vault", Context.MODE_PRIVATE);
    }

    // ---------- storage ----------

    public List<PasswordEntry> all() {
        List<PasswordEntry> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp.getString("entries", "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(PasswordEntry.fromJson(arr.getJSONObject(i)));
        } catch (Exception ignored) {}
        return out;
    }

    public PasswordEntry find(String id) {
        for (PasswordEntry e : all()) if (e.id.equals(id)) return e;
        return null;
    }

    public void save(PasswordEntry entry) {
        List<PasswordEntry> list = all();
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(entry.id)) { list.set(i, entry); replaced = true; break; }
        }
        if (!replaced) list.add(entry);
        write(list);
    }

    public void delete(String id) {
        List<PasswordEntry> list = all();
        for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(id)) { list.remove(i); break; }
        write(list);
    }

    public void eraseAll() {
        sp.edit().remove("entries").apply();
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            ks.deleteEntry(ALIAS_BIO);
            ks.deleteEntry(ALIAS_PLAIN);
        } catch (Exception ignored) {}
    }

    private void write(List<PasswordEntry> list) {
        try {
            JSONArray arr = new JSONArray();
            for (PasswordEntry e : list) arr.put(e.toJson());
            sp.edit().putString("entries", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    // ---------- crypto ----------

    /** True if the phone has an enrolled strong biometric (fingerprint or class-3 face). */
    public static boolean biometricAvailable(Context ctx) {
        BiometricManager bm = ctx.getSystemService(BiometricManager.class);
        return bm != null && bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                == BiometricManager.BIOMETRIC_SUCCESS;
    }

    private static SecretKey key(boolean bio, boolean forceNew) throws GeneralSecurityException {
        try {
            String alias = bio ? ALIAS_BIO : ALIAS_PLAIN;
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (forceNew && ks.containsAlias(alias)) ks.deleteEntry(alias);
            if (!ks.containsAlias(alias)) {
                KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(alias,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256);
                if (bio) {
                    b.setUserAuthenticationRequired(true);
                    b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG); // auth on every use
                    b.setInvalidatedByBiometricEnrollment(true);
                }
                KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                kg.init(b.build());
                return kg.generateKey();
            }
            return (SecretKey) ks.getKey(alias, null);
        } catch (GeneralSecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneralSecurityException(e);
        }
    }

    /** Cipher to encrypt a new password. A biometric cipher must still be authorised with BiometricPrompt. */
    public Cipher encryptCipher(boolean bio) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance(TRANSFORM);
        try {
            c.init(Cipher.ENCRYPT_MODE, key(bio, false));
        } catch (KeyPermanentlyInvalidatedException e) {
            c.init(Cipher.ENCRYPT_MODE, key(bio, true));   // fingerprints changed: start a fresh key
        }
        return c;
    }

    /** Throws KeyPermanentlyInvalidatedException if fingerprints changed since this entry was saved. */
    public Cipher decryptCipher(PasswordEntry e) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance(TRANSFORM);
        c.init(Cipher.DECRYPT_MODE, key(e.requireBio, false), new GCMParameterSpec(128, e.iv));
        return c;
    }

    public static String decrypt(Cipher authorised, PasswordEntry e) throws GeneralSecurityException {
        return new String(authorised.doFinal(e.ct), StandardCharsets.UTF_8);
    }
}
