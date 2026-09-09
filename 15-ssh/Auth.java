import java.nio.charset.StandardCharsets;

/**
 * Auth — what the client actually signs to prove it owns a private key.
 *
 * Layout from RFC 4252 section 7. The first field is the one that matters:
 * including the session identifier binds the signature to ONE connection, so a
 * hostile server cannot replay it elsewhere. Experiment 5 removes it and shows
 * the replay succeed.
 */
final class Auth {

    static final byte SSH_MSG_USERAUTH_REQUEST = 50;

    /** Pass sessionId = null to build the unsafe v1 blob. */
    static byte[] blob(byte[] sessionId, String user, byte[] publicKeyBlob) {
        byte[] head = sessionId == null ? new byte[0] : Wire.string(sessionId);
        return Wire.cat(
                head,                                   // <-- binds to this connection
                new byte[]{SSH_MSG_USERAUTH_REQUEST},
                Wire.string(user),
                Wire.string("ssh-connection"),
                Wire.string("publickey"),
                new byte[]{1},                          // boolean TRUE: a real attempt
                Wire.string("ssh-ed25519"),
                Wire.string(publicKeyBlob));
    }

    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
