package com.codex.shinsegaeattendance;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class LoginCredentials {
    static final int MAX_ACCOUNTS = 10;
    private static final String PREFS = "encrypted_login_credentials";
    private static final String LEGACY_VALUE = "ciphertext";
    private static final String ACCOUNTS_VALUE = "accounts_ciphertext";
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

    static List<Account> readAccounts(Context context) throws Exception {
        SharedPreferences prefs = preferences(context);
        String stored = prefs.getString(ACCOUNTS_VALUE, "");
        if (!stored.isEmpty()) {
            JSONObject payload = new JSONObject(decrypt(stored));
            JSONArray array = payload.getJSONArray("accounts");
            List<Account> accounts = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                accounts.add(new Account(item.getString("username"), item.getString("password")));
            }
            return accounts;
        }

        // Existing single-account installs keep their saved credential and
        // gain it as the first entry in the ordered account list.
        String legacy = prefs.getString(LEGACY_VALUE, "");
        if (legacy.isEmpty()) return new ArrayList<>();
        JSONObject item = new JSONObject(decrypt(legacy));
        List<Account> accounts = new ArrayList<>();
        accounts.add(new Account(item.getString("username"), item.getString("password")));
        writeAccounts(context, accounts);
        prefs.edit().remove(LEGACY_VALUE).apply();
        return accounts;
    }

    static boolean addOrUpdate(Context context, String username, String password) throws Exception {
        List<Account> accounts = readAccounts(context);
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).username.equalsIgnoreCase(username)) {
                accounts.set(i, new Account(username, password));
                writeAccounts(context, accounts);
                return false;
            }
        }
        if (accounts.size() >= MAX_ACCOUNTS) throw new IllegalStateException("Account limit reached");
        accounts.add(new Account(username, password));
        writeAccounts(context, accounts);
        return true;
    }

    static void remove(Context context, int index) throws Exception {
        List<Account> accounts = readAccounts(context);
        if (index < 0 || index >= accounts.size()) throw new IllegalArgumentException("Invalid account index");
        accounts.remove(index);
        writeAccounts(context, accounts);
    }

    private static void writeAccounts(Context context, List<Account> accounts) throws Exception {
        JSONArray array = new JSONArray();
        for (Account account : accounts) {
            JSONObject item = new JSONObject();
            item.put("username", account.username);
            item.put("password", account.password);
            array.put(item);
        }
        JSONObject payload = new JSONObject();
        payload.put("accounts", array);
        preferences(context).edit().putString(ACCOUNTS_VALUE, encrypt(payload.toString())).apply();
    }

    private static String encrypt(String plainText) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        byte[] payload = new byte[cipher.getIV().length + encrypted.length];
        System.arraycopy(cipher.getIV(), 0, payload, 0, cipher.getIV().length);
        System.arraycopy(encrypted, 0, payload, cipher.getIV().length, encrypted.length);
        String result = Base64.encodeToString(payload, Base64.NO_WRAP);
        Arrays.fill(encrypted, (byte) 0);
        Arrays.fill(payload, (byte) 0);
        return result;
    }

    private static String decrypt(String stored) throws Exception {
        byte[] payload = Base64.decode(stored, Base64.NO_WRAP);
        if (payload.length <= IV_LENGTH) throw new IllegalStateException("Invalid credential payload");
        byte[] iv = Arrays.copyOfRange(payload, 0, IV_LENGTH);
        byte[] encrypted = Arrays.copyOfRange(payload, IV_LENGTH, payload.length);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        byte[] plain = cipher.doFinal(encrypted);
        String result = new String(plain, StandardCharsets.UTF_8);
        Arrays.fill(plain, (byte) 0);
        Arrays.fill(encrypted, (byte) 0);
        Arrays.fill(payload, (byte) 0);
        Arrays.fill(iv, (byte) 0);
        return result;
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
