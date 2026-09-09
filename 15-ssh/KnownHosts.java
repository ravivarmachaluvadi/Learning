import java.util.LinkedHashMap;
import java.util.Map;
import java.security.PublicKey;

/**
 * KnownHosts — the entire trust policy of SSH, in about ten lines.
 *
 * There is no certificate authority here and no third party to ask. The only
 * question the client can answer is "is this the key I saw last time?".
 */
final class KnownHosts {

    private final Map<String, String> remembered = new LinkedHashMap<>();

    String check(String host, PublicKey offered) {
        String fp = Wire.fingerprint(offered);
        String known = remembered.get(host);

        if (known == null) {
            // TRUST ON FIRST USE: nothing is verified here. The client is asking
            // a human to eyeball a fingerprint it has no way to check itself.
            remembered.put(host, fp);
            return "UNKNOWN HOST. fingerprint " + fp.substring(0, 26) + "...\n"
                 + "             -> prompt the human, then remember it. NOTHING WAS VERIFIED.";
        }
        if (known.equals(fp)) {
            return "ok, same key as last time (" + fp.substring(0, 26) + "...)";
        }
        // The alarm. Note it cannot tell an attack from an honest server rebuild.
        return "!! REMOTE HOST IDENTIFICATION HAS CHANGED !!\n"
             + "             expected " + known.substring(0, 26) + "...\n"
             + "             got      " + fp.substring(0, 26) + "...\n"
             + "             -> connection REFUSED. Could be an attack, could be a rebuild.";
    }
}
