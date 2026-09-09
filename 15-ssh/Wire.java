import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.*;
import java.util.Base64;

/**
 * Wire — SSH's on-the-wire encoding rules, and nothing else.
 *
 * SSH does not send "a key". It sends a byte string built by a fixed rule, and
 * every implementation on earth agrees on that rule. This class IS that rule.
 * Experiment 1 proves it: a key built here is read back correctly by the real
 * `ssh-keygen` binary.
 */
final class Wire {

    /** RFC 4251 "string": a 4-byte big-endian length, then that many bytes. */
    static byte[] string(byte[] payload) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(payload.length >>> 24); o.write(payload.length >>> 16);
        o.write(payload.length >>> 8);  o.write(payload.length);
        o.writeBytes(payload);
        return o.toByteArray();
    }

    static byte[] string(String s) { return string(s.getBytes(StandardCharsets.UTF_8)); }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.writeBytes(p);
        return o.toByteArray();
    }

    static byte[] sha256(byte[] b) {
        try { return MessageDigest.getInstance("SHA-256").digest(b); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    /**
     * The public-key blob: string("ssh-ed25519") || string(32 raw key bytes).
     * This exact blob is what gets base64'd into a .pub file and into
     * authorized_keys and known_hosts.
     */
    static byte[] ed25519Blob(PublicKey pk) {
        return cat(string("ssh-ed25519"), string(rawEd25519(pk)));
    }

    /**
     * A fingerprint is NOT a separate identity — it is just SHA-256 of the blob
     * above. That is why comparing fingerprints is exactly as good as comparing
     * keys, and why a fingerprint is safe to read aloud over the phone.
     */
    static String fingerprint(PublicKey pk) {
        return "SHA256:" + Base64.getEncoder().withoutPadding()
                .encodeToString(sha256(ed25519Blob(pk)));
    }

    static String authorizedKeysLine(PublicKey pk, String comment) {
        return "ssh-ed25519 " + Base64.getEncoder().encodeToString(ed25519Blob(pk)) + " " + comment;
    }

    /**
     * Ed25519 stores a public key as 32 little-endian bytes of the point's
     * y-coordinate, with the top bit of the last byte carrying the sign of x.
     * Java hands us y as a BigInteger (big-endian), so we reverse and re-set
     * that bit. Fiddly, but this is the difference between "a key" and "a key
     * OpenSSH will accept".
     */
    static byte[] rawEd25519(PublicKey pk) {
        EdECPoint point = ((EdECPublicKey) pk).getPoint();
        byte[] be = point.getY().toByteArray();
        byte[] le = new byte[32];
        int start = 0;
        while (start < be.length && be[start] == 0) start++;
        int len = be.length - start;
        for (int i = 0; i < len; i++) le[i] = be[be.length - 1 - i];
        if (point.isXOdd()) le[31] |= (byte) 0x80;
        return le;
    }

    /** The inverse: rebuild a usable PublicKey from 32 raw bytes off the wire. */
    static PublicKey parseEd25519(byte[] raw32) {
        try {
            byte[] le = raw32.clone();
            boolean xOdd = (le[31] & 0x80) != 0;
            le[31] &= (byte) 0x7F;
            byte[] be = new byte[32];
            for (int i = 0; i < 32; i++) be[i] = le[31 - i];
            EdECPoint point = new EdECPoint(xOdd, new BigInteger(1, be));
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new EdECPublicKeySpec(NamedParameterSpec.ED25519, point));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static byte[] sign(PrivateKey sk, byte[] message) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(sk); s.update(message); return s.sign();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static boolean verify(PublicKey pk, byte[] message, byte[] sig) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(pk); s.update(message); return s.verify(sig);
        } catch (Exception e) { return false; }
    }

    static String hex(byte[] b, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(b.length, max); i++) sb.append(String.format("%02x", b[i]));
        if (b.length > max) sb.append("...");
        return sb.toString();
    }
}
