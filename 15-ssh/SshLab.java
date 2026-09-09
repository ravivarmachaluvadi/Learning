import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/**
 * SshLab — SSH's cryptographic core, built from JDK primitives only.
 *
 * Run:  java SshLab.java
 *
 * Nothing here is a mock. The key exchange really agrees a secret, the
 * signatures really verify, and the man-in-the-middle really reads the
 * plaintext when we take the host key check away. Every number printed is
 * computed while the program runs.
 *
 * What this file deliberately leaves out: packet framing, algorithm
 * negotiation, rekeying, and compression. Those are engineering, not the idea.
 * The real ssh binary supplies them in ssh-real.sh.
 */
public class SshLab {

    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of("out"));
        System.out.println("""
            ==========================================================================
             SSH LAB — how two strangers on a hostile network end up trusting
             each other well enough to run commands.
            ==========================================================================""");
        exp1_whatAKeyActuallyIs();
        exp2_agreeingASecretInPublic();
        exp3_theManInTheMiddle();
        exp4_trustOnFirstUse();
        exp5_provingWhoTheUserIs();
        exp6_forwardSecrecy();
        exp7_whatEncryptionDoesNotHide();
        System.out.println("""

            ==========================================================================
             Done. Next: ./ssh-real.sh  — the same ideas, driven through the real
             OpenSSH client and server, where the files and the error messages are
             the ones you will actually meet in production.
            ==========================================================================""");
    }

    // ====================================================================== 1
    static void exp1_whatAKeyActuallyIs() throws Exception {
        head(1, "What an SSH key actually is",
             "A key is not a magic object. It is a byte string built by a rule.",
             "We build one here, then hand it to the real ssh-keygen binary and",
             "ask what it thinks. If the two agree, our rule is THE rule.");

        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] blob = Wire.ed25519Blob(kp.getPublic());
        String line = Wire.authorizedKeysLine(kp.getPublic(), "built-by-SshLab");
        Files.writeString(Path.of("out/java-made.pub"), line + "\n");

        System.out.println("  the public key blob, in pieces:");
        System.out.println("    string(\"ssh-ed25519\")     = 4 length bytes + 11 name bytes = 15 bytes");
        System.out.println("    string(<32 raw key bytes>) = 4 length bytes + 32 key bytes  = 36 bytes");
        System.out.println("    total blob                 = " + blob.length + " bytes");
        System.out.println("    raw key bytes (hex)        = " + Wire.hex(Wire.rawEd25519(kp.getPublic()), 16));
        System.out.println();
        System.out.println("  a fingerprint is just SHA-256 of that blob, base64'd:");
        System.out.println("    ours          " + Wire.fingerprint(kp.getPublic()));

        String fromOpenSsh = run("ssh-keygen", "-lf", "out/java-made.pub");
        if (fromOpenSsh == null) {
            System.out.println("    ssh-keygen    (not found on this machine — comparison skipped)");
        } else {
            String theirs = Arrays.stream(fromOpenSsh.trim().split("\\s+"))
                    .filter(t -> t.startsWith("SHA256:")).findFirst().orElse("(unparsed)");
            System.out.println("    ssh-keygen    " + theirs);
            System.out.println("    ssh-keygen's full verdict: " + fromOpenSsh.trim());
            System.out.println();
            System.out.println("  MATCH: " + theirs.equals(Wire.fingerprint(kp.getPublic())));
        }
        note("The private half never appeared in any of this. Everything above is",
             "public, and it is exactly what lives in authorized_keys and known_hosts.");
    }

    // ====================================================================== 2
    static void exp2_agreeingASecretInPublic() throws Exception {
        head(2, "Agreeing a secret while everyone is listening",
             "The client and the server have never met and share no password.",
             "They must end up holding the same secret number. Every byte they",
             "send is printed below — assume Eve copies all of it.");

        KeyPair c = Kex.ephemeral(), s = Kex.ephemeral();
        System.out.println("  client -> server   Q_C = " + Wire.hex(Kex.pub(c), 12) + "  (public, 32 bytes)");
        System.out.println("  server -> client   Q_S = " + Wire.hex(Kex.pub(s), 12) + "  (public, 32 bytes)");
        byte[] kClient = Kex.agree(c, s.getPublic());
        byte[] kServer = Kex.agree(s, c.getPublic());
        System.out.println();
        System.out.println("  client computes K = " + Wire.hex(kClient, 16));
        System.out.println("  server computes K = " + Wire.hex(kServer, 16));
        System.out.println("  same secret?      " + Arrays.equals(kClient, kServer));

        System.out.println();
        System.out.println("  Eve saw Q_C and Q_S. She has no private half, so the best she can do");
        System.out.println("  is bring her own keypair and compute a secret with it:");
        KeyPair eve = Kex.ephemeral();
        byte[] kEve = Kex.agree(eve, s.getPublic());
        System.out.println("    Eve computes    K = " + Wire.hex(kEve, 16));
        System.out.println("    matches theirs?   " + Arrays.equals(kEve, kClient) + "   <- she is locked out");

        byte[] h = Kex.exchangeHash(Kex.pub(c), Kex.pub(s), kClient);
        System.out.println();
        System.out.println("  One shared secret is then stretched into several keys, exactly the way");
        System.out.println("  SSH does it — SHA256(K || H || letter || session_id):");
        for (char letter : new char[]{'C', 'D', 'E', 'F'}) {
            byte[] k = Kex.deriveKey(kClient, h, letter, h);
            System.out.printf("    key '%c'  %s   (%s)%n", letter, Wire.hex(k, 12), Kex.roleOf(letter));
        }
        note("Nothing so far proves WHO is on the other end. Both sides now share a",
             "secret with *somebody*. Experiment 3 is about who that somebody is.");
    }

    // ====================================================================== 3
    static void exp3_theManInTheMiddle() throws Exception {
        head(3, "Encryption without identity is worthless",
             "Mallory sits on the cafe wifi. She does not break the maths. She",
             "simply runs experiment 2 twice: once facing the client, once facing",
             "the server. Watch what she can read.");

        KeyPair client = Kex.ephemeral(), server = Kex.ephemeral();
        KeyPair mToClient = Kex.ephemeral(), mToServer = Kex.ephemeral();

        // Client thinks it is talking to the server. It is talking to Mallory.
        byte[] kClientSide = Kex.agree(client, mToClient.getPublic());
        byte[] kMalloryC   = Kex.agree(mToClient, client.getPublic());
        byte[] kMalloryS   = Kex.agree(mToServer, server.getPublic());
        byte[] kServerSide = Kex.agree(server, mToServer.getPublic());

        String secret = "cat /home/ravi/.aws/credentials";
        byte[] ct = Crypto.seal(kClientSide, secret);
        System.out.println("  client encrypts and sends: " + Wire.hex(ct, 20));
        System.out.println("  Mallory decrypts it as:    \"" + Crypto.open(kMalloryC, ct) + "\"   <-- plaintext");
        byte[] reSealed = Crypto.seal(kMalloryS, secret);
        System.out.println("  Mallory re-encrypts to the server, which reads: \""
                + Crypto.open(kServerSide, reSealed) + "\"");
        System.out.println();
        System.out.println("  Both ends see a perfectly healthy encrypted session. No error, no");
        System.out.println("  warning, no slowdown. This is the attack SSH exists to stop.");

        System.out.println();
        System.out.println("  --- now add the host key signature ---");
        System.out.println("  The server has a LONG-LIVED key (its host key). It signs the exchange");
        System.out.println("  hash H, which covers both public halves. Mallory cannot forge that");
        System.out.println("  signature, because she does not have the server's private host key.");

        KeyPair serverHostKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();

        // Each side hashes the exchange AS IT SAW IT. Neither of them was talking
        // to the other, so all three inputs differ: the peer's public value, its
        // own public value, and the shared secret.
        byte[] hAtServer = Kex.exchangeHash(Kex.pub(mToServer), Kex.pub(server), kServerSide);
        byte[] hAtClient = Kex.exchangeHash(Kex.pub(client), Kex.pub(mToClient), kClientSide);
        byte[] sig = Wire.sign(serverHostKey.getPrivate(), hAtServer);

        System.out.println();
        System.out.println("  the two ends hashed different conversations:");
        System.out.println("    H computed by the server = " + Wire.hex(hAtServer, 14));
        System.out.println("    H computed by the client = " + Wire.hex(hAtClient, 14));
        System.out.println();
        System.out.println("  the server signed ITS hash. the client checks that signature");
        System.out.println("  against the hash IT computed:");
        System.out.println("    verify(server host key, H_as_client_saw_it) = "
                + Wire.verify(serverHostKey.getPublic(), hAtClient, sig)
                + "   <- the signature is over a conversation the client was never in");

        System.out.println();
        System.out.println("  So Mallory signs with a host key of her OWN. That signature is");
        System.out.println("  mathematically valid:");
        KeyPair malloryHostKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] mSig = Wire.sign(malloryHostKey.getPrivate(), hAtClient);
        System.out.println("    verify(Mallory host key, H_as_client_saw_it) = "
                + Wire.verify(malloryHostKey.getPublic(), hAtClient, mSig) + "   <- valid!");
        System.out.println();
        System.out.println("  The signature is fine. The KEY is wrong. Everything now depends on the");
        System.out.println("  client already knowing which host key is the right one:");
        System.out.println("    real server fingerprint : " + Wire.fingerprint(serverHostKey.getPublic()));
        System.out.println("    Mallory's fingerprint   : " + Wire.fingerprint(malloryHostKey.getPublic()));
        note("A signature only proves 'the holder of key X signed this'. It never",
             "proves X is the key you wanted. That memory is the whole job of",
             "known_hosts — experiment 4.");
    }

    // ====================================================================== 4
    static void exp4_trustOnFirstUse() throws Exception {
        head(4, "Trust on first use, and the hole in it",
             "SSH has no certificate authority by default. The client simply",
             "remembers the host key it saw the first time, and screams if it",
             "ever changes. Here is that policy, and both ways it plays out.");

        KeyPair real = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair mallory = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String host = "prod-db.example.com";

        System.out.println("  --- story A: the honest first connection ---");
        KnownHosts kh = new KnownHosts();
        System.out.println("  connect 1  " + kh.check(host, real.getPublic()));
        System.out.println("  connect 2  " + kh.check(host, real.getPublic()));
        System.out.println("  connect 3, with Mallory in the middle:");
        System.out.println("             " + kh.check(host, mallory.getPublic()));

        System.out.println();
        System.out.println("  --- story B: Mallory was there for the FIRST connection ---");
        KnownHosts kh2 = new KnownHosts();
        System.out.println("  connect 1  " + kh2.check(host, mallory.getPublic()));
        System.out.println("  connect 2  " + kh2.check(host, mallory.getPublic()));
        System.out.println("  connect 3, Mallory has gone home, real server answers:");
        System.out.println("             " + kh2.check(host, real.getPublic()));
        System.out.println();
        System.out.println("  Read story B again. The alarm fired on the HONEST server. TOFU pinned");
        System.out.println("  the attacker and then defended her. The protection is real but it is");
        System.out.println("  anchored to whatever you accepted the first time.");
        note("This is the single biggest difference from TLS. TLS asks a certificate",
             "authority 'is this key right?'. SSH asks your own past self.");
    }

    // ====================================================================== 5
    static void exp5_provingWhoTheUserIs() throws Exception {
        head(5, "Proving who the USER is — and why the signature covers the session",
             "The server now knows it is talking to a client. It still has no idea",
             "which human. The client proves it by signing a challenge. The exact",
             "contents of what gets signed decide whether the scheme is safe.");

        KeyPair userKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] userBlob = Wire.ed25519Blob(userKey.getPublic());
        System.out.println("  the user's key lives on the laptop; the server has only this line:");
        System.out.println("    " + Wire.authorizedKeysLine(userKey.getPublic(), "ravi@laptop").substring(0, 74) + "...");
        System.out.println();

        // Two DIFFERENT servers => two different session identifiers.
        byte[] sessionEvil = Wire.sha256("session-with-evil.example.com".getBytes(StandardCharsets.UTF_8));
        byte[] sessionProd = Wire.sha256("session-with-prod.example.com".getBytes(StandardCharsets.UTF_8));
        System.out.println("  session id, evil.example.com : " + Wire.hex(sessionEvil, 12));
        System.out.println("  session id, prod.example.com : " + Wire.hex(sessionProd, 12));
        System.out.println("  (a session id is the first exchange hash H — it is unique per connection)");

        System.out.println();
        System.out.println("  --- design v1: sign only 'I am ravi, let me in' ---");
        byte[] blobV1 = Auth.blob(null, "ravi", userBlob);
        byte[] sigV1 = Wire.sign(userKey.getPrivate(), blobV1);
        System.out.println("  ravi logs in to evil.example.com. Its operator captures the signature.");
        System.out.println("  she replays it, unchanged, to prod.example.com:");
        // prod parses the key out of its own authorized_keys file, exactly as a
        // real server does, then checks the signature it was handed.
        PublicKey asProdSeesIt = Wire.parseEd25519(Wire.rawEd25519(userKey.getPublic()));
        boolean replayedV1 = Wire.verify(asProdSeesIt, Auth.blob(null, "ravi", userBlob), sigV1);
        System.out.println("    prod accepts the replayed signature? " + replayedV1 + "   <-- she is in");

        System.out.println();
        System.out.println("  --- design v2: sign the session id too (this is what SSH does) ---");
        byte[] sigV2 = Wire.sign(userKey.getPrivate(), Auth.blob(sessionEvil, "ravi", userBlob));
        System.out.println("  same capture, same replay to prod. prod recomputes what SHOULD have");
        System.out.println("  been signed, using ITS OWN session id:");
        boolean replayedV2 = Wire.verify(userKey.getPublic(), Auth.blob(sessionProd, "ravi", userBlob), sigV2);
        System.out.println("    prod accepts the replayed signature? " + replayedV2 + "  <-- refused");
        boolean honest = Wire.verify(userKey.getPublic(), Auth.blob(sessionProd, "ravi", userBlob),
                Wire.sign(userKey.getPrivate(), Auth.blob(sessionProd, "ravi", userBlob)));
        System.out.println("    a genuine login to prod still works?  " + honest);
        note("The signature is bound to one connection, so it is worthless anywhere",
             "else. This is why any server you ssh into cannot turn around and use",
             "your key elsewhere — and it is a real protocol field, not a nicety.");
    }

    // ====================================================================== 6
    static void exp6_forwardSecrecy() throws Exception {
        head(6, "Why the host key must not be able to decrypt anything",
             "Two designs for getting a session key. Both look fine today. We then",
             "leak the server's long-lived private key a year later and see which",
             "recorded conversations can be read.");

        String message = "psql -h prod -c 'select * from payments limit 1'";

        System.out.println("  --- design A: client picks the key, encrypts it TO the host key ---");
        KeyPair rsaHost = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        byte[] sessionKeyA = new byte[32];
        SecureRandom.getInstanceStrong().nextBytes(sessionKeyA);
        Cipher enc = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        enc.init(Cipher.ENCRYPT_MODE, rsaHost.getPublic());
        byte[] wrapped = enc.doFinal(sessionKeyA);
        byte[] recordedA = Crypto.seal(sessionKeyA, message);
        System.out.println("  Eve records both blobs and files them away.");
        System.out.println("    wrapped session key : " + Wire.hex(wrapped, 16) + " (" + wrapped.length + " bytes)");
        System.out.println("    encrypted traffic   : " + Wire.hex(recordedA, 16));

        System.out.println();
        System.out.println("  --- design B: ephemeral Diffie-Hellman, host key only SIGNS ---");
        KeyPair c = Kex.ephemeral(), s = Kex.ephemeral();
        byte[] sessionKeyB = Kex.agree(c, s.getPublic());
        byte[] recordedB = Crypto.seal(sessionKeyB, message);
        System.out.println("    encrypted traffic   : " + Wire.hex(recordedB, 16));
        // The ephemeral private halves are discarded, exactly as a real client does.
        c = null; s = null;
        System.gc();

        System.out.println();
        System.out.println("  === one year later: the server is compromised and its private host");
        System.out.println("      key is stolen. Eve opens her archive. ===");
        Cipher dec = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        dec.init(Cipher.DECRYPT_MODE, rsaHost.getPrivate());
        byte[] recovered = dec.doFinal(wrapped);
        System.out.println("  design A: unwraps the session key " + Wire.hex(recovered, 12));
        System.out.println("            and reads: \"" + Crypto.open(recovered, recordedA) + "\"");
        System.out.println("  design B: the host key never touched the session key. The two");
        System.out.println("            ephemeral private halves were discarded at the end of the");
        System.out.println("            connection and exist nowhere. Eve holds "
                + recordedB.length + " bytes of noise.");
        note("This property is called forward secrecy: past conversations stay",
             "private even after today's long-term key is stolen. SSH-2 uses design B.",
             "SSH-1 used design A — one of several reasons it is gone.");
    }

    // ====================================================================== 7
    static void exp7_whatEncryptionDoesNotHide() throws Exception {
        head(7, "What the encryption does NOT hide",
             "Encryption hides the content of each packet. It does not hide that a",
             "packet happened, how big it was, or when it went.");

        byte[] key = new byte[32];
        SecureRandom.getInstanceStrong().nextBytes(key);
        String[] commands = {"ls", "whoami", "sudo systemctl restart payments-api"};
        System.out.println("  same key, three commands:");
        for (String cmd : commands) {
            byte[] ct = Crypto.seal(key, cmd);
            System.out.printf("    %-38s -> ciphertext %3d bytes%n", "\"" + cmd + "\"", ct.length);
        }
        System.out.println();
        System.out.println("  An observer who cannot read one byte still learns the length of every");
        System.out.println("  command. Real SSH pads to a block boundary, which blurs this but does");
        System.out.println("  not remove it.");
        System.out.println();
        System.out.println("  Worse, in an interactive shell SSH sends one packet PER KEYSTROKE, as");
        System.out.println("  you type, so it can echo characters back with no lag:");
        String password = "hunter2!";
        System.out.println("    typing an " + password.length() + "-character password produces "
                + password.length() + " packets, each " + Crypto.seal(key, "x").length + " bytes.");
        System.out.println("    the observer learns the password LENGTH exactly, and the gaps between");
        System.out.println("    keystrokes leak which letter pairs were typed (published attack:");
        System.out.println("    Song, Wagner & Tian, USENIX 2001).");
        note("This is why 'it is encrypted' is not the end of a threat model, and why",
             "public-key auth beats typing a password over an interactive session.");
    }

    // ================================================================= helpers
    static void head(int n, String title, String... lines) {
        System.out.println("\n\n--------------------------------------------------------------------------");
        System.out.println(" EXPERIMENT " + n + " — " + title);
        System.out.println("--------------------------------------------------------------------------");
        for (String l : lines) System.out.println(" " + l);
        System.out.println();
    }

    static void note(String... lines) {
        System.out.println();
        System.out.println("  >> " + String.join("\n  >> ", lines));
    }

    /** Shell out, returning null if the binary is missing. */
    static String run(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return p.waitFor() == 0 ? out : null;
        } catch (Exception e) { return null; }
    }
}
