package com.keyrelay.app.ui;

import android.app.Activity;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.CancellationSignal;

import javax.crypto.Cipher;

/** Thin wrapper around the framework BiometricPrompt with a CryptoObject (the key only works after a fingerprint). */
final class Auth {

    interface Done {
        void ok(Cipher authorisedCipher);
        /** message == null means the user simply cancelled. */
        void fail(String message);
    }

    private Auth() {}

    static void prompt(Activity a, String title, String subtitle, Cipher cipher, Done done) {
        BiometricPrompt prompt = new BiometricPrompt.Builder(a)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton("Cancel", a.getMainExecutor(), (d, w) -> done.fail(null))
                .build();

        prompt.authenticate(new BiometricPrompt.CryptoObject(cipher), new CancellationSignal(),
                a.getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        done.ok(result.getCryptoObject().getCipher());
                    }

                    @Override
                    public void onAuthenticationError(int code, CharSequence msg) {
                        // 5 = canceled, 10 = user canceled, 13 = negative button (handled above)
                        if (code == 5 || code == 10 || code == 13) done.fail(null);
                        else done.fail(msg == null ? "Authentication failed" : msg.toString());
                    }
                });
    }
}
