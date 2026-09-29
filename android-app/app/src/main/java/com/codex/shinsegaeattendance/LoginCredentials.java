package com.codex.shinsegaeattendance;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class LoginCredentials {
    private static final String PREFS = "encrypted_login_credentials";
    private static final String VALUE = "ciphertext";
    private static final String KEY_ALIAS = "shinsegae_login_aes_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;

    static final class Account {
        final String username;
        final String password;

        Account(String username, String password) {
            this.username = username;
            this.password = password;
        }
    }

    private LoginCredentials() {}

    static boolean isSaved(Context context) {
        return !preferences(context).getString(VALUE, "").isEmpty();
    }

    static void save(Context context, String username, String password) throws Exception {
        JSONObject account = new JSONObject();
        account.put("username", username);
        account.put("password", password);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(account.toString().getBytes(StandardCharsets.UTF_8));
        byte[] payload = new byte[cipher.getIV().length + encrypted.length];
        System.arraycopy(cipher.getIV(), 0, payload, 0, cipher.getIV().length);
        System.arraycopy(encrypted, 0, payload, cipher.getIV().length, encrypted.length);
        preferences(context).edit().putString(VALUE, Base64.encodeToString(payload, Base64.NO_WRAP)).apply();
        Arrays.fill(encrypted, (byte) 0);
        Arrays.fill(payload, (byte) 0);
    }

    static Account read(Context context) throws Exception {
        String stored = preferences(context).getString(VALUE, "");
        if (stored.isEmpty()) return null;
        byte[] payload = Base64.decode(stored, Base64.NO_WRAP);
        if (payload.length <= IV_LENGTH) throw new IllegalStateException("Invalid credential payload");
        byte[] iv = Arrays.copyOfRange(payload, 0, IV_LENGTH);
        byte[] encrypted = Arrays.copyOfRange(payload, IV_LENGTH, payload.length);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        byte[] plain = cipher.doFinal(encrypted);
        JSONObject account = new JSONObject(new String(plain, StandardCharsets.UTF_8));
        Account result = new Account(account.getString("username"), account.getString("password"));
        Arrays.fill(plain, (byte) 0);
        Arrays.fill(encrypted, (byte) 0);
        Arrays.fill(payload, (byte) 0);
        return result;
    }

    static void clear(Context context) {
        preferences(context).edit().remove(VALUE).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey existing = (SecretKey) store.getKey(KEY_ALIAS, null);
        if (existing != null) return existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
