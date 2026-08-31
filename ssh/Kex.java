import java.security.*;
import javax.crypto.KeyAgreement;

/**
 * Kex — the key exchange half of SSH's transport layer.
 *
 * "Ephemeral" is the load-bearing word. Both keypairs here are made for ONE
 * connection and thrown away when it ends. That single choice is what gives
 * SSH forward secrecy (experiment 6).
 */
final class Kex {

    static KeyPair ephemeral() {
        try { return KeyPairGenerator.getInstance("X25519").generateKeyPair(); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    /** The 32 public bytes that go on the wire. */
    static byte[] pub(KeyPair kp) {
        try {
            java.security.interfaces.XECPublicKey x = (java.security.interfaces.XECPublicKey) kp.getPublic();
            byte[] be = x.getU().toByteArray();
            byte[] le = new byte[32];
            int start = 0;
            while (start < be.length && be[start] == 0) start++;
            int len = Math.min(be.length - start, 32);
            for (int i = 0; i < len; i++) le[i] = be[be.length - 1 - i];
            return le;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /**
     * My private half + your public half -> the same number on both sides.
     * Neither private half ever crosses the network.
     */
    static byte[] agree(KeyPair mine, PublicKey yours) {
        try {
            KeyAgreement ka = KeyAgreement.getInstance("X25519");
            ka.init(mine.getPrivate());
            ka.doPhase(yours, true);
            return ka.generateSecret();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /**
     * The exchange hash H. Real SSH hashes rather more into it (both version
     * strings and both algorithm-negotiation messages), which is what stops an
     * attacker downgrading the cipher list. We hash the essential part: both
     * public halves and the shared secret.
     *
     * INVARIANT: H differs if ANY byte of the exchange differed. That is why a
     * man in the middle cannot make the client's H match the server's H.
     */
    static byte[] exchangeHash(byte[] qc, byte[] qs, byte[] k) {
        return Wire.sha256(Wire.cat(Wire.string(qc), Wire.string(qs), Wire.string(k)));
    }

    /**
     * One secret is stretched into six independent keys, so that (for example)
     * the client-to-server encryption key is useless for forging a server MAC.
     * Layout copied from RFC 4253 section 7.2.
     */
    static byte[] deriveKey(byte[] k, byte[] h, char letter, byte[] sessionId) {
        return Wire.sha256(Wire.cat(k, h, new byte[]{(byte) letter}, sessionId));
    }

    static String roleOf(char letter) {
        return switch (letter) {
            case 'A' -> "initial IV, client to server";
            case 'B' -> "initial IV, server to client";
            case 'C' -> "encryption key, client to server";
            case 'D' -> "encryption key, server to client";
            case 'E' -> "integrity key, client to server";
            case 'F' -> "integrity key, server to client";
            default  -> "unused";
        };
    }
}
