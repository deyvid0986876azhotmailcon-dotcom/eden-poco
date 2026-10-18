package dev.poco.asistente.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** The preferences contain only ciphertext and a fresh IV, never a plaintext API key. */
public final class KeyVault {
    private static final String ALIAS = "dev.poco.asistente.gemini.v1";
    private static final byte[] AAD = ALIAS.getBytes(StandardCharsets.UTF_8);
    private final SharedPreferences prefs;
    public KeyVault(Context context) { prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE); }
    private KeyStore keyStore() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null); return store;
    }
    public synchronized boolean hasKey() { return prefs.contains("ciphertext"); }
    public synchronized void save(String plaintext) throws Exception {
        if (plaintext == null || !plaintext.matches("[A-Za-z0-9._-]{20,200}")) throw new IllegalArgumentException("Formato de clave inválido.");
        KeyStore store = keyStore();
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).setRandomizedEncryptionRequired(true).build());
            generator.generateKey();
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, (SecretKey) store.getKey(ALIAS, null)); cipher.updateAAD(AAD);
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        if (!prefs.edit().putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)).commit()) throw new IllegalStateException("No se guardó la clave cifrada.");
    }
    public synchronized String load() throws Exception {
        if (!hasKey()) return "";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP);
        cipher.init(Cipher.DECRYPT_MODE, (SecretKey) keyStore().getKey(ALIAS, null), new GCMParameterSpec(128, iv)); cipher.updateAAD(AAD);
        byte[] plain = cipher.doFinal(Base64.decode(prefs.getString("ciphertext", ""), Base64.NO_WRAP));
        try { return new String(plain, StandardCharsets.UTF_8); }
        finally { java.util.Arrays.fill(plain, (byte) 0); }
    }
    public synchronized void clear() throws Exception {
        if (!prefs.edit().clear().commit()) throw new IllegalStateException("No se pudo borrar la clave.");
        keyStore().deleteEntry(ALIAS);
    }
}
