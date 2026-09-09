import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/**
 * BrokenAuth — for EXERCISES.md, rung 6 (Debug).
 *
 * A team wrote their own SSH-style public-key login for an internal admin tool.
 * The code below is their server. It is careful: it builds the RFC 4252 signed
 * blob correctly, it binds the signature to the session id, and it rejects
 * tampered signatures. Their test suite passes. It has been in production for
 * eight months.
 *
 * Run:  java BrokenAuth.java
 *
 * Two checks run below. One passes. One reports a catastrophe.
 *
 * Your job:
 *   1. Which check fails, and what exactly did the attacker have to possess in
 *      order to get in?
 *   2. Exactly one line in `Server.authenticate` is responsible. Which one, and
 *      what question does it answer instead of the question it should answer?
 *   3. Experiment 3 in SshLab.java ends with the sentence "The signature is
 *      fine. The KEY is wrong." Explain this bug using that sentence.
 *   4. The fix is two lines. Write them, and say which file on a real SSH
 *      server plays the role of the data structure you must consult.
 *   5. Would binding the signature to the session id have prevented this?
 *      Why is that a different protection from the one that is missing?
 *
 * Answers in SOLUTIONS.md.
 */
public class BrokenAuth {

    public static void main(String[] args) throws Exception {
        // The server was set up with exactly one authorized user.
        KeyPair ravi = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Server server = new Server();
        server.authorize("ravi", ravi.getPublic());
        System.out.println("server's authorized_keys holds one entry:");
        System.out.println("  ravi  " + Wire.fingerprint(ravi.getPublic()));
        System.out.println();

        // ---------- check 1: the real ravi logs in ----------
        byte[] session1 = server.newSession();
        byte[] proof1 = sign(ravi, session1, "ravi");
        boolean ok1 = server.authenticate("ravi", ravi.getPublic(), session1, proof1);
        System.out.println("CHECK 1  the genuine user logs in");
        System.out.println("         accepted = " + ok1 + "   " + (ok1 ? "PASS" : "FAIL"));

        // ---------- check 2: a stranger logs in ----------
        // Mallory has never been given access. She has no stolen key. She simply
        // generates a brand new keypair on her own laptop, five seconds ago.
        KeyPair mallory = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        System.out.println();
        System.out.println("CHECK 2  a stranger with a key she generated herself");
        System.out.println("         her key  " + Wire.fingerprint(mallory.getPublic()));
        System.out.println("         (this fingerprint is nowhere in authorized_keys)");
        byte[] session2 = server.newSession();
        byte[] proof2 = sign(mallory, session2, "ravi");
        boolean ok2 = server.authenticate("ravi", mallory.getPublic(), session2, proof2);
        System.out.println("         accepted = " + ok2 + "   " + (ok2 ? "FAIL — she is in" : "PASS"));

        // ---------- check 3: sanity, the crypto itself is fine ----------
        byte[] session3 = server.newSession();
        byte[] tampered = sign(mallory, session3, "ravi");
        tampered[0] ^= 0x01;
        boolean ok3 = server.authenticate("ravi", mallory.getPublic(), session3, tampered);
        System.out.println();
        System.out.println("CHECK 3  a corrupted signature is still rejected");
        System.out.println("         accepted = " + ok3 + "   " + (ok3 ? "FAIL" : "PASS"));
        System.out.println();
        System.out.println("The signature checking works perfectly. That is the trap.");
        System.out.println("Read the questions at the top of this file.");
    }

    static byte[] sign(KeyPair kp, byte[] sessionId, String user) {
        return Wire.sign(kp.getPrivate(),
                Auth.blob(sessionId, user, Wire.ed25519Blob(kp.getPublic())));
    }

    /** The team's server. */
    static class Server {
        private final Map<String, PublicKey> authorizedKeys = new HashMap<>();
        private int counter = 0;

        void authorize(String user, PublicKey key) { authorizedKeys.put(user, key); }

        byte[] newSession() {
            return Wire.sha256(("session-" + (++counter)).getBytes(StandardCharsets.UTF_8));
        }

        boolean authenticate(String user, PublicKey offeredKey, byte[] sessionId, byte[] signature) {
            if (!authorizedKeys.containsKey(user)) return false;

            // Rebuild exactly what the client should have signed, including the
            // session id so the signature cannot be replayed on another
            // connection. This part is correct.
            byte[] expected = Auth.blob(sessionId, user, Wire.ed25519Blob(offeredKey));

            // Check the signature.
            return Wire.verify(offeredKey, expected, signature);
        }
    }
}
