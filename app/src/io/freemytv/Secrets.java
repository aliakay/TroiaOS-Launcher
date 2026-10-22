package io.freemytv;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Passwords and tokens typed on the TV (the Wi-Fi password, the Hue Bridge's key, the Home Assistant token) are kept
 * encrypted with a key that lives in the TV's Android Keystore and never leaves it: even a copy of the app's files
 * doesn't reveal them. AES-GCM; the stored text is base64(iv + ciphertext).
 */
final class Secrets {
    private Secrets() {}

    // The alias keeps the old name (Toria) so secrets saved before the rename still open.
    private static final String ALIAS = "toria-secrets", STORE = "AndroidKeyStore";

    private static synchronized SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(STORE);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE);
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }

    /** The encrypted form of `plain` ("" stays ""). Throws if the TV can't encrypt: then nothing is saved. */
    static String seal(String plain) throws Exception {
        if (plain == null || plain.isEmpty()) return "";
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = c.getIV(), ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(ByteBuffer.allocate(1 + iv.length + ct.length).put((byte) iv.length).put(iv).put(ct).array(),
                Base64.NO_WRAP);
    }

    /** The plain text, or "" if it can't be read (e.g. the app's data was restored without its key). */
    static String open(String sealed) {
        if (sealed == null || sealed.isEmpty()) return "";
        try {
            ByteBuffer b = ByteBuffer.wrap(Base64.decode(sealed, Base64.NO_WRAP));
            byte[] iv = new byte[b.get() & 255];
            b.get(iv);
            byte[] ct = new byte[b.remaining()];
            b.get(ct);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AppLog.w("secrets: can't read a saved secret (" + e.getClass().getSimpleName() + ")");
            return "";
        }
    }
}
