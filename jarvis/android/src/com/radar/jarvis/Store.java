package com.radar.jarvis;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Ajustes de la app. Las claves (Anthropic, ElevenLabs) se guardan cifradas con una clave del Keystore de Android
 *  que nunca sale del teléfono. No van en el código, ni en git, ni en copias de seguridad (allowBackup=false). */
public final class Store {
    private static final String ALIAS = "jarvis-secretos";
    private final SharedPreferences p;

    Store(Context c) { p = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE); }

    public String get(String k, String def) { return p.getString(k, def); }
    public void put(String k, String v) { p.edit().putString(k, v).apply(); }
    public boolean flag(String k) { return p.getBoolean(k, false); }
    public void flag(String k, boolean v) { p.edit().putBoolean(k, v).apply(); }
    public int num(String k) { return p.getInt(k, 0); }
    public void num(String k, int v) { p.edit().putInt(k, v).apply(); }

    public boolean hasSecret(String k) { return !secret(k).isEmpty(); }

    public String secret(String k) {
        String enc = p.getString("sec_" + k, null);
        if (enc == null) return "";
        try {
            byte[] all = Base64.decode(enc, Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    public void secret(String k, String value) {
        if (value == null || value.trim().isEmpty()) { p.edit().remove("sec_" + k).apply(); return; }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV(), ct = c.doFinal(value.trim().getBytes("UTF-8"));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            p.edit().putString("sec_" + k, Base64.encodeToString(all, Base64.NO_WRAP)).apply();
        } catch (Exception e) {
            throw new RuntimeException("No pude guardar la clave de forma segura: " + e.getMessage());
        }
    }

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return g.generateKey();
    }
}
