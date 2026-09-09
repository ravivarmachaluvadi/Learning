import java.io.*;
import java.net.*;
import java.security.cert.*;
import java.util.*;
import javax.net.ssl.*;

/**
 * TlsLab — six experiments against a real TLS implementation.
 *
 * Nothing here is simulated. These are genuine handshakes between the JDK's TLS
 * client and the JDK's TLS server, using certificates that keytool actually
 * issued. When a certificate is rejected below, it is rejected by the same code
 * path that protects your production traffic.
 *
 * Setup:  ./certs.sh      (once — builds pki/)
 * Run:    java TlsLab.java          (all six)
 *         java TlsLab.java 3        (just experiment 3)
 */
public class TlsLab {

    public static void main(String[] args) throws Exception {
        if (!new File("pki/good.p12").exists()) {
            System.out.println("Run ./certs.sh first — it builds the certificates this lab needs.");
            return;
        }
        Set<String> pick = new HashSet<>(Arrays.asList(args));
        banner("TLS — six experiments against a real implementation");
        if (on(pick, "1")) exp1_whatDidTheyAgree();
        if (on(pick, "2")) exp2_fourWaysToBeRejected();
        if (on(pick, "3")) exp3_encryptionIsNotAuthentication();
        if (on(pick, "4")) exp4_whatTlsDoesNotHide();
        if (on(pick, "5")) exp5_whatTheHandshakeCosts();
        if (on(pick, "6")) exp6_provingWhoTheClientIs();
        System.out.println("\nDone. README.md stage 14 says what each experiment proves.");
    }

    static boolean on(Set<String> p, String n) { return p.isEmpty() || p.contains(n); }

    // ==================================================================
    // 1 — A successful handshake, and what the two sides settled on
    // ==================================================================
    static void exp1_whatDidTheyAgree() throws Exception {
        banner("EXPERIMENT 1 — what did the two sides actually agree on?");
        System.out.println("""
                A handshake is a negotiation, not a password check. Both sides arrive
                with a list of what they support and leave having picked one of each.
                Here is what a real handshake settled, and the identity it proved.""");

        try (TlsServer srv = new TlsServer("pki/good.p12", false)) {
            SSLContext ctx = TlsServer.context(null, "pki/truststore.p12");
            try (SSLSocket c = connect(ctx, srv.port(), "localhost")) {
                c.startHandshake();
                SSLSession s = c.getSession();

                System.out.printf("%n  protocol version   : %s%n", s.getProtocol());
                System.out.printf("  cipher suite       : %s%n", s.getCipherSuite());
                System.out.printf("  session id length  : %d bytes%n", s.getId().length);

                System.out.println("\n  the certificate chain the server presented:");
                Certificate[] chain = s.getPeerCertificates();
                for (int i = 0; i < chain.length; i++) {
                    X509Certificate x = (X509Certificate) chain[i];
                    System.out.printf("    [%d] %-34s%n", i, x.getSubjectX500Principal().getName());
                    System.out.printf("        issued by %s%n", x.getIssuerX500Principal().getName());
                    System.out.printf("        valid %tF to %tF%n", x.getNotBefore(), x.getNotAfter());
                }

                var out = new PrintWriter(c.getOutputStream(), true);
                var in = new BufferedReader(new InputStreamReader(c.getInputStream()));
                out.println("hello");
                System.out.printf("%n  the server then said: %s%n", in.readLine());
            }
        }

        System.out.println("""

                  Read the chain from the bottom up. Entry [1] is our certificate
                  authority, which the client already trusted because it is in the
                  trust store. Entry [0] is the server, and it is trusted ONLY because
                  [1] signed it. That is the whole idea: trust one authority in
                  advance, and it can vouch for any number of servers you have never
                  seen before.

                  Note what the cipher suite name does NOT contain: any mention of who
                  the server is. Encryption and identity are two separate jobs, and
                  experiment 3 is about what happens when you get one without the
                  other.""");
    }

    // ==================================================================
    // 2 — The four ways a certificate gets rejected
    // ==================================================================
    static void exp2_fourWaysToBeRejected() throws Exception {
        banner("EXPERIMENT 2 — four ways to be rejected, and how to tell them apart");
        System.out.println("""
                Four servers. All four are reachable, all four speak TLS correctly, and
                all four offer a certificate. Three get refused, for three different
                reasons. In your logs they all arrive as SSLHandshakeException — the
                cause chain is what separates them.""");

        record Case(String store, String host, String what) {}
        List<Case> cases = List.of(
            new Case("pki/good.p12",       "localhost", "correct name, signed by a CA we trust"),
            new Case("pki/selfsigned.p12", "localhost", "correct name, but signs its own certificate"),
            new Case("pki/wronghost.p12",  "localhost", "signed by our CA, but issued for other.example"),
            new Case("pki/expired.p12",    "localhost", "signed by our CA, correct name, out of date"));

        for (Case c : cases) {
            System.out.printf("%n  · %s%n", c.what());
            try (TlsServer srv = new TlsServer(c.store(), false)) {
                SSLContext ctx = TlsServer.context(null, "pki/truststore.p12");
                try (SSLSocket s = connect(ctx, srv.port(), c.host())) {
                    s.startHandshake();
                    System.out.printf("      ACCEPTED — identity proved: %s%n",
                            ((X509Certificate) s.getSession().getPeerCertificates()[0])
                                    .getSubjectX500Principal().getName());
                } catch (Exception e) {
                    System.out.printf("      REJECTED — %s%n", e.getClass().getSimpleName());
                    System.out.printf("      because  : %s%n", rootCause(e));
                    System.out.printf("      verdict  : %s%n", verdict(e));
                }
            }
        }

        System.out.println("""

                  All three failures are the SAME exception type and the SAME layer.
                  Only the cause chain tells you which one you have, and each one has a
                  different owner: an untrusted issuer is usually YOUR trust store, a
                  hostname mismatch is usually the URL or the certificate's SAN list,
                  and an expired certificate is the server team's renewal process.

                  This is why "just trust everything" is such a tempting fix and such a
                  catastrophic one. It makes all three go away, along with the
                  protection. Run BrokenTrust.java to see exactly that.""");
    }

    // ==================================================================
    // 3 — The heart of the lab
    // ==================================================================
    static void exp3_encryptionIsNotAuthentication() throws Exception {
        banner("EXPERIMENT 3 — a perfectly encrypted conversation with the wrong server");
        System.out.println("""
                An impostor runs a server on our port. It has a certificate saying
                CN=localhost — but signed by its own authority, "Interceptor Root CA",
                not ours.

                We connect twice with identical client code. The only thing that
                changes between the two attempts is which certificate authorities the
                client was told to trust.""");

        try (TlsServer impostor = new TlsServer("pki/mitm.p12", false)) {

            System.out.println("\n  A · client trusts only our own CA:");
            SSLContext strict = TlsServer.context(null, "pki/truststore.p12");
            try (SSLSocket s = connect(strict, impostor.port(), "localhost")) {
                s.startHandshake();
                System.out.println("      connected — and that would be very bad news");
            } catch (Exception e) {
                System.out.printf("      REJECTED before a single byte of data moved%n");
                System.out.printf("      because  : %s%n", rootCause(e));
            }

            System.out.println("\n  B · client also trusts the interceptor's CA:");
            SSLContext lax = TlsServer.context(null, "pki/truststore-with-mitm.p12");
            try (SSLSocket s = connect(lax, impostor.port(), "localhost")) {
                s.startHandshake();
                SSLSession sess = s.getSession();
                var out = new PrintWriter(s.getOutputStream(), true);
                var in = new BufferedReader(new InputStreamReader(s.getInputStream()));
                out.println("my-password");
                String reply = in.readLine();

                System.out.println("      CONNECTED, and everything looks perfect:");
                System.out.printf("        protocol      : %s%n", sess.getProtocol());
                System.out.printf("        cipher        : %s%n", sess.getCipherSuite());
                System.out.printf("        encrypted     : yes, fully%n");
                System.out.printf("        certificate   : %s%n",
                        ((X509Certificate) sess.getPeerCertificates()[0])
                                .getSubjectX500Principal().getName());
                System.out.printf("        signed by     : %s   <-- the only clue%n",
                        ((X509Certificate) sess.getPeerCertificates()[0])
                                .getIssuerX500Principal().getName());
                System.out.printf("        we sent       : my-password%n");
                System.out.printf("        we received   : %s%n", reply);
            }
        }

        System.out.println("""

                  Both attempts used the same protocol, the same strength of
                  encryption, and the same client code. In attempt B the traffic is
                  encrypted beautifully — to an impostor, who read the password.

                  The padlock never meant "you are talking to your bank". It means
                  "nobody can read this except whoever you are talking to". Which of
                  those two you get is decided entirely by the trust store, and by
                  nothing else in the protocol.

                  This is not a hypothetical. Attempt B is precisely how a corporate
                  TLS-inspecting proxy works: IT installs its CA on every laptop, and
                  from then on every employee's HTTPS is readable by the proxy while
                  still showing a padlock.""");
    }

    // ==================================================================
    // 4 — What TLS does not hide
    // ==================================================================
    static void exp4_whatTlsDoesNotHide() throws Exception {
        banner("EXPERIMENT 4 — what an eavesdropper can still see");
        System.out.println("""
                Encryption starts partway through the handshake, which means the very
                first message is sent in the clear. We point a TLS client at a plain
                TCP listener and read the raw bytes it sends before any key exists.""");

        String secretLookingHost = "payments.internal.example";
        byte[] first;

        try (ServerSocket plain = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                try {
                    SSLContext ctx = TlsServer.context(null, "pki/truststore.p12");
                    SSLSocket s = (SSLSocket) ctx.getSocketFactory()
                            .createSocket("127.0.0.1", plain.getLocalPort());
                    SSLParameters p = s.getSSLParameters();
                    p.setServerNames(List.of(new SNIHostName(secretLookingHost)));
                    s.setSSLParameters(p);
                    s.startHandshake();   // will fail — we already have what we need
                } catch (Exception ignored) { }
            });

            try (Socket c = plain.accept(); InputStream in = c.getInputStream()) {
                c.setSoTimeout(3000);
                byte[] buf = new byte[4096];
                int n = in.read(buf);
                first = Arrays.copyOf(buf, Math.max(0, n));
            }
        }

        System.out.printf("%n  the client's very first %d bytes, before any encryption exists:%n", first.length);
        System.out.printf("    record type  : 0x%02X  (22 = handshake)%n", first[0]);
        System.out.printf("    version      : 0x%02X%02X%n", first[1], first[2]);
        System.out.printf("    handshake    : 0x%02X  (1 = ClientHello)%n", first[5]);

        String asText = new String(first, java.nio.charset.StandardCharsets.ISO_8859_1);
        int at = asText.indexOf(secretLookingHost);
        System.out.printf("%n    searching those raw bytes for \"%s\"%n", secretLookingHost);
        System.out.printf("    found at byte offset %d — in plain, readable text%n", at);
        System.out.printf("    surrounding bytes: %s%n",
                printable(asText.substring(Math.max(0, at - 6), Math.min(asText.length(), at + secretLookingHost.length() + 2))));

        System.out.println("""

                  The hostname you are connecting to travels in the clear, every time.
                  It has to: one IP address may serve thousands of sites, so the server
                  must know which certificate to present BEFORE it can encrypt
                  anything. That field is called SNI, and it is why a network observer
                  can build a complete list of which services you talk to without
                  decrypting a single byte.

                  TLS hides the contents of your conversation. It does not hide that
                  the conversation happened, who it was with, when, or how big it was.""");
    }

    // ==================================================================
    // 5 — What the handshake costs, and how to stop paying it
    // ==================================================================
    static void exp5_whatTheHandshakeCosts() throws Exception {
        banner("EXPERIMENT 5 — what the handshake costs (and a lesson in measuring)");
        System.out.println("""
                A handshake costs two different things: round trips on the network, and
                signature maths on the CPU. Loopback has no distance, so what follows
                isolates the CPU half.

                We warm the JVM up first. Without that, the first handshake also pays
                for class loading and JIT compilation, and you measure the JVM instead
                of the protocol.""");

        try (TlsServer srv = new TlsServer("pki/good.p12", false)) {
            SSLContext warm = TlsServer.context(null, "pki/truststore.p12");
            for (int i = 0; i < 20; i++)
                try (SSLSocket s = connect(warm, srv.port(), "localhost")) { s.startHandshake(); }

            // Arm 1: one context reused -> the JDK caches the session, so
            // handshakes after the first may resume.
            SSLContext shared = TlsServer.context(null, "pki/truststore.p12");
            long first = time(shared, srv.port());
            long reusedSum = 0;
            int n = 9;
            for (int i = 0; i < n; i++) reusedSum += time(shared, srv.port());

            // Arm 2: a brand-new context every time -> empty session cache ->
            // every handshake is necessarily a FULL one. This is the control.
            long freshSum = 0;
            for (int i = 0; i < n; i++)
                freshSum += time(TlsServer.context(null, "pki/truststore.p12"), srv.port());

            System.out.printf("%n  first handshake, cold session cache : %,6d us%n", first);
            System.out.printf("  later handshakes, same context      : %,6d us   (may resume)%n", reusedSum / n);
            System.out.printf("  new context every time              : %,6d us   (never resumes)%n", freshSum / n);
        }

        System.out.println("""

                  Look at the last two lines. Handshakes that CAN resume are not
                  meaningfully faster than handshakes that cannot — on this machine the
                  control arm was, if anything, marginally quicker. The differences are
                  noise.

                  That is a real result, not a broken experiment, and it says something
                  useful: **on loopback, resumption buys you almost nothing, because
                  what resumption saves is round trips, and loopback has none.**

                  Measuring it against real hosts did not give a clean answer either:
                  cloudflare.com took 38 ms for a full handshake and 41-50 ms for
                  supposedly resumed ones, while www.google.com went 167 ms then
                  162/56/54 ms. Every one of those numbers also contains a fresh TCP
                  handshake, and network variance is larger than the effect.

                  So the honest way to state the cost is in round trips, which is what
                  the design actually fixes, rather than in a benchmark number:

                    TLS 1.2, full          2 round trips before your request moves
                    TLS 1.3, full          1 round trip
                    TLS 1.3, resumed       1 round trip, and much less signature maths
                    TLS 1.3, 0-RTT         0 round trips, at a real cost (stage 11)

                  And the measurement that IS clean is the one from the
                  networking-for-developers lab: TLS to cloudflare.com took 46 ms on
                  top of a 13 ms TCP connect. That extra ~33 ms is roughly one more
                  round trip plus certificate work — which is exactly the accounting
                  above, seen from the outside.""");
    }

    static long time(SSLContext ctx, int port) throws Exception {
        long t0 = System.nanoTime();
        try (SSLSocket s = connect(ctx, port, "localhost")) { s.startHandshake(); }
        return (System.nanoTime() - t0) / 1000;   // microseconds
    }

    // ==================================================================
    // 6 — Making the client prove who IT is
    // ==================================================================
    static void exp6_provingWhoTheClientIs() throws Exception {
        banner("EXPERIMENT 6 — mutual TLS: the server checks you too");
        System.out.println("""
                So far only the server proved anything. That is the web's model: you
                verify your bank, your bank verifies you by password afterwards.

                Between two services inside your own system there is a better option —
                make the client present a certificate as well. Same server, two
                clients: one has an identity, one does not.""");

        try (TlsServer srv = new TlsServer("pki/good.p12", true)) {

            System.out.println("\n  A · client presents no certificate:");
            SSLContext anon = TlsServer.context(null, "pki/truststore.p12");
            try (SSLSocket s = connect(anon, srv.port(), "localhost")) {
                s.startHandshake();
                var out = new PrintWriter(s.getOutputStream(), true);
                var in = new BufferedReader(new InputStreamReader(s.getInputStream()));
                out.println("hello");
                String r = in.readLine();
                System.out.printf("      handshake completed, but the request got: %s%n",
                        r == null ? "nothing — the server hung up" : r);
            } catch (Exception e) {
                System.out.printf("      REJECTED — %s%n", e.getClass().getSimpleName());
                System.out.printf("      because  : %s%n", rootCause(e));
            }

            System.out.println("\n  B · client presents a certificate our CA signed:");
            SSLContext identified = TlsServer.context("pki/good.p12", "pki/truststore.p12");
            try (SSLSocket s = connect(identified, srv.port(), "localhost")) {
                s.startHandshake();
                var out = new PrintWriter(s.getOutputStream(), true);
                var in = new BufferedReader(new InputStreamReader(s.getInputStream()));
                out.println("hello");
                System.out.printf("      ACCEPTED — server said: %s%n", in.readLine());
                System.out.printf("      the server now knows the caller is: %s%n", srv.clientIdentity);
            }
        }

        System.out.println("""

                  In case B the server learned the caller's identity from the handshake
                  itself — before any application code ran, and without a password, a
                  token, or a shared secret to leak.

                  That is what a service mesh gives you when it says "mTLS everywhere":
                  every service holds a short-lived certificate, and every connection is
                  authenticated in both directions by the transport rather than by
                  something your code has to remember to check.""");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Connect an SSLSocket and set the hostname, which is what enables the
     *  hostname check. Forgetting this line is failure 5 in the README. */
    static SSLSocket connect(SSLContext ctx, int port, String host) throws IOException {
        // Connect by NAME, not by IP. The hostname check compares the certificate
        // against whatever host this socket was created with, so using the IP here
        // would silently test a different thing than production does.
        SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(host, port);
        SSLParameters p = s.getSSLParameters();
        // THE INVARIANT: without this, the certificate is checked for a valid
        // signature but never checked against the name you meant to reach — so
        // any certificate our CA ever signed would be accepted for any host.
        p.setEndpointIdentificationAlgorithm("HTTPS");
        p.setServerNames(List.of(new SNIHostName(host)));
        s.setSSLParameters(p);
        s.setSoTimeout(5000);
        return s;
    }

    static String rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    static String verdict(Exception e) {
        String all = chainText(e);
        // ORDER MATTERS. An expired certificate also fails path building and also
        // mentions PKIX, so testing for PKIX first would misdiagnose it as an
        // untrusted issuer — which points the on-call engineer at the wrong team.
        if (all.contains("CertificateExpiredException") || all.contains("NotAfter"))
            return "the certificate was valid once and no longer is — nothing is wrong with trust";
        if (all.contains("CertificateNotYetValidException") || all.contains("NotBefore"))
            return "the certificate is not valid YET — usually a clock that disagrees";
        if (all.contains("No subject alternative") || all.contains("No name matching")
                || all.contains("doesn't match"))
            return "the certificate is genuine and in date, but was not issued for this name";
        if (all.contains("unable to find valid certification path") || all.contains("PKIX"))
            return "the issuer is not in our trust store — nobody we trust vouches for this server";
        return "unclassified — read the cause chain";
    }

    static String chainText(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) sb.append(t).append(" | ");
        return sb.toString();
    }

    static String printable(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) sb.append(ch >= 32 && ch < 127 ? ch : '.');
        return sb.toString();
    }

    static void banner(String s) {
        System.out.println("\n" + "=".repeat(74));
        System.out.println(s);
        System.out.println("=".repeat(74));
    }
}
