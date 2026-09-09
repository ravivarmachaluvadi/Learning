import java.io.*;
import java.net.*;
import java.security.*;
import javax.net.ssl.*;

/**
 * A real TLS server. Not a simulation — this speaks the actual protocol, using
 * the JDK's own implementation and certificates made by keytool.
 *
 * Which identity it presents is chosen by which keystore you hand it, and that
 * one choice is what produces every certificate failure in the lab.
 */
final class TlsServer implements AutoCloseable {

    private final SSLServerSocket socket;
    private volatile boolean running = true;

    /** The last handshake's negotiated parameters — what the two sides agreed on. */
    volatile String negotiatedProtocol = "-";
    volatile String negotiatedCipher = "-";
    volatile String clientIdentity = "(none — client did not present a certificate)";

    /**
     * @param keystore    which identity to present, e.g. pki/good.p12
     * @param requireClientCert  when true, the server refuses clients that cannot
     *                           prove their own identity. This is mutual TLS.
     */
    TlsServer(String keystore, boolean requireClientCert) throws Exception {
        SSLContext ctx = context(keystore, requireClientCert ? "pki/truststore.p12" : null);
        socket = (SSLServerSocket) ctx.getServerSocketFactory()
                .createServerSocket(0, 16, InetAddress.getLoopbackAddress());
        if (requireClientCert) socket.setNeedClientAuth(true);
        Thread.ofVirtual().name("tls-accept").start(this::acceptLoop);
    }

    int port() { return socket.getLocalPort(); }

    private void acceptLoop() {
        while (running) {
            try {
                SSLSocket c = (SSLSocket) socket.accept();
                Thread.ofVirtual().start(() -> handle(c));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(SSLSocket c) {
        try (c) {
            c.startHandshake();
            SSLSession s = c.getSession();
            negotiatedProtocol = s.getProtocol();
            negotiatedCipher = s.getCipherSuite();
            try {
                clientIdentity = s.getPeerPrincipal().getName();
            } catch (SSLPeerUnverifiedException e) {
                clientIdentity = "(none — client did not present a certificate)";
            }
            var in = new BufferedReader(new InputStreamReader(c.getInputStream()));
            var out = new PrintWriter(c.getOutputStream(), true);
            String line = in.readLine();
            out.println("secret-balance:42 (you sent: " + line + ")");
        } catch (Exception ignored) {
            // A rejected handshake lands here. From the server's side that is
            // normal: the client hung up because it did not like our certificate.
        }
    }

    @Override public void close() throws IOException { running = false; socket.close(); }

    // ------------------------------------------------------------------
    // Building an SSLContext from a keystore file. This is the same code a
    // Spring Boot app runs when you set server.ssl.key-store in properties.
    // ------------------------------------------------------------------
    static SSLContext context(String keystorePath, String truststorePath) throws Exception {
        char[] pw = "changeit".toCharArray();
        SSLContext ctx = SSLContext.getInstance("TLS");

        KeyManager[] kms = null;
        if (keystorePath != null) {
            KeyStore ks = load(keystorePath, pw);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, pw);
            kms = kmf.getKeyManagers();
        }

        TrustManager[] tms = null;
        if (truststorePath != null) {
            KeyStore ts = load(truststorePath, pw);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ts);
            tms = tmf.getTrustManagers();
        }

        ctx.init(kms, tms, null);
        return ctx;
    }

    static KeyStore load(String path, char[] pw) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(path)) { ks.load(in, pw); }
        return ks;
    }
}
