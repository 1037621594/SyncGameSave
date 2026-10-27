package com.example.syncgamesave.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.KeyPairGeneratorSpec;
import android.util.Base64;
import com.example.syncgamesave.model.S3Config;
import com.google.gson.Gson;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Calendar;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.x500.X500Principal;

/** RSA wraps a per-write AES key, supporting Android 5.0 without plaintext fallback. */
public class SecureConfigStore {
    private static final String ALIAS = "syncgamesave.credentials.v1";
    private final Context context;
    private final SharedPreferences preferences;
    private final Gson gson = new Gson();

    public SecureConfigStore(Context context) {
        this.context = context.getApplicationContext();
        // Keep the original encrypted store and key alias so existing AK/SK can be migrated.
        preferences = this.context.getSharedPreferences("secure_qiniu", Context.MODE_PRIVATE);
    }

    @SuppressWarnings("deprecation")
    private KeyStore keyStore() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            Calendar start = Calendar.getInstance();
            Calendar end = Calendar.getInstance();
            end.add(Calendar.YEAR, 30);
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
            generator.initialize(new KeyPairGeneratorSpec.Builder(context).setAlias(ALIAS)
                    .setSubject(new X500Principal("CN=SyncGameSave")).setSerialNumber(BigInteger.ONE)
                    .setStartDate(start.getTime()).setEndDate(end.getTime()).setKeySize(2048).build());
            generator.generateKeyPair();
        }
        return store;
    }

    public synchronized S3Config read() throws Exception {
        String payload = preferences.getString("payload", null);
        if (payload == null) return new S3Config();
        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.DECRYPT_MODE, keyStore().getKey(ALIAS, null));
        byte[] key = rsa.doFinal(decode(preferences.getString("key", "")));
        try {
            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, decode(preferences.getString("iv", ""))));
            return gson.fromJson(new String(aes.doFinal(decode(payload)), StandardCharsets.UTF_8), S3Config.class);
        } finally { Arrays.fill(key, (byte) 0); }
    }

    public synchronized void write(S3Config config) throws Exception {
        byte[] key = new byte[32];
        byte[] iv = new byte[12];
        SecureRandom random = new SecureRandom();
        random.nextBytes(key);
        random.nextBytes(iv);
        try {
            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] payload = aes.doFinal(gson.toJson(config).getBytes(StandardCharsets.UTF_8));
            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.ENCRYPT_MODE, keyStore().getCertificate(ALIAS).getPublicKey());
            boolean saved = preferences.edit().putString("payload", encode(payload))
                    .putString("key", encode(rsa.doFinal(key))).putString("iv", encode(iv)).commit();
            if (!saved) throw new java.io.IOException("S3 配置保存失败");
        } finally { Arrays.fill(key, (byte) 0); }
    }

    private static String encode(byte[] value) { return Base64.encodeToString(value, Base64.NO_WRAP); }
    private static byte[] decode(String value) { return Base64.decode(value, Base64.NO_WRAP); }
}
