import java.io.*;
import java.security.cert.X509Certificate;
import javax.net.ssl.*;

/**
 * BrokenTrust — for EXERCISES.md, rung 6 (Debug).
 *
 * A developer hit "unable to find valid certification path to requested target"
 * in staging. They searched for the error, found a highly-upvoted answer, pasted
 * it in, and the error went away. Everything works now. Tests pass. It shipped.
 *
 * Run:  ./certs.sh   (once)
 *       java BrokenTrust.java
 *
 * Two checks run below. One passes, one fails. Your job:
 *   1. Which check fails, and what exactly did the client hand over?
 *   2. Which method in this file causes it, and what is the significance of its
 *      body being empty?
 *   3. The original error was real — the certificate genuinely could not be
 *      verified. Name the correct fix, and say why it is more work than this one.
 *   4. There is a SECOND, independent way to break exactly this protection, and
 *      TlsLab.connect() has a comment naming it. What is it, and why would it
 *      still fail check 2 even if this file's TrustManager were correct?
 *
 * Answers in SOLUTIONS.md.
 */
public class BrokenTrust {

    public static void main(String[] args) throws Exception {
        if (!new File("pki/good.p12").exists()) {
            System.out.println("Run ./certs.sh first.");
            return;
        }
        System.out.println("=".repeat(68));
        System.out.println("BrokenTrust — one 'fix', two checks");
        System.out.println("=".repeat(68));
        System.out.println("""

                The claim under test: "this client talks securely to our payments
                service." Check 1 uses the real server. Check 2 uses an impostor
                holding a certificate signed by an authority nobody should trust.""");

        // CHECK 1 — the legitimate server.
        System.out.printf("%nCHECK 1  connects_to_the_real_server%n");
        try (TlsServer real = new TlsServer("pki/good.p12", false)) {
            String reply = call(real.port());
            boolean ok = reply != null && reply.startsWith("secret-balance");
            System.out.printf("         server replied : %s%n", reply);
            System.out.printf("         -> %s%n", ok ? "PASS" : "FAIL");
        }

        // CHECK 2 — an impostor. A correct client MUST refuse this.
        System.out.printf("%nCHECK 2  refuses_an_impostor%n");
        try (TlsServer impostor = new TlsServer("pki/mitm.p12", false)) {
            String reply;
            boolean refused;
            try {
                reply = call(impostor.port());
                refused = false;
            } catch (Exception e) {
                reply = e.getClass().getSimpleName();
                refused = true;
            }
            System.out.printf("         impostor cert  : CN=localhost, signed by \"Interceptor Root CA\"%n");
            System.out.printf("         we sent        : my-password%n");
            System.out.printf("         outcome        : %s%n",
                    refused ? "refused (" + reply + ")" : "CONNECTED — impostor replied: " + reply);
            System.out.printf("         -> %s%s%n", refused ? "PASS" : "FAIL",
                    refused ? "" : "  (the password was handed to an impostor, over a perfectly encrypted channel)");
        }

        System.out.println("\nNow read trustManager() below. The bug is the absence of code.");
    }

    /** Send one line to a TLS server and read the reply. */
    static String call(int port) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{ trustManager() }, new java.security.SecureRandom());

        try (SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket("localhost", port)) {
            s.setSoTimeout(5000);
            s.startHandshake();
            var out = new PrintWriter(s.getOutputStream(), true);
            var in = new BufferedReader(new InputStreamReader(s.getInputStream()));
            out.println("my-password");
            return in.readLine();
        }
    }

    /**
     * Found on the internet under "how to fix PKIX path building failed".
     * It does fix it.
     */
    static X509TrustManager trustManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }
}
