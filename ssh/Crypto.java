import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Crypto — the record layer, reduced to two calls.
 *
 * AES-GCM is an AEAD: it encrypts AND authenticates in one step, so a modified
 * ciphertext fails to open rather than decrypting to garbage. Real SSH uses
 * chacha20-poly1305 or aes-gcm for the same reason.
 */
final class Crypto {

    static byte[] seal(byte[] key32, String plaintext) {
        try {
            byte[] iv = new byte[12];
            SecureRandom.getInstanceStrong().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key32, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[12 + ct.length];
            System.arraycopy(iv, 0, out, 0, 12);
            System.arraycopy(ct, 0, out, 12, ct.length);
            return out;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Returns the plaintext, or a marker string if the key is wrong. */
    static String open(byte[] key32, byte[] sealed) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key32, "AES"),
                   new GCMParameterSpec(128, Arrays.copyOfRange(sealed, 0, 12)));
            return new String(c.doFinal(Arrays.copyOfRange(sealed, 12, sealed.length)),
                              StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<<unreadable: wrong key>>";
        }
    }
}
