import java.io.*;
import java.net.*;
import javax.net.ssl.*;

/**
 * Probe — measures one request phase at a time, and turns a failure into a
 * diagnosis.
 *
 * The whole point of this lab is that `httpClient.send(...)` collapses six
 * independent systems into one line, so when it fails you learn almost nothing.
 * Probe un-collapses it: each phase is timed separately, and every exception is
 * mapped to the layer that actually broke and to whether retrying can help.
 */
final class Probe {

    /** Timings for one request, in milliseconds. -1 means "never got this far". */
    record Phases(long dns, long tcp, long tls, long total, String address, String note) {
        long appTime() { return total - Math.max(0, dns) - Math.max(0, tcp) - Math.max(0, tls); }
    }

    /** What a failure actually means, and what to do about it. */
    record Diagnosis(String layer, String meaning, String retry) {}

    static long ms(long startNanos) { return (System.nanoTime() - startNanos) / 1_000_000; }

    /** PHASE 1 — turn a name into addresses. Costs nothing on the network when
     *  cached, and can cost seconds when the resolver misbehaves. */
    static long timeResolve(String host) {
        long t0 = System.nanoTime();
        try { InetAddress.getAllByName(host); } catch (UnknownHostException e) { return -1; }
        return ms(t0);
    }

    /** PHASE 2 — the three-way handshake. One round trip, minimum.
     *  This is the phase whose FAILURE MODE carries the most information:
     *  a fast refusal and a slow timeout mean opposite things. */
    static long timeConnect(String host, int port, int timeoutMs) throws IOException {
        long t0 = System.nanoTime();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return ms(t0);
        }
    }

    /** PHASE 3 — the TLS handshake, on top of an established TCP connection.
     *  Costs at least one more round trip, plus certificate verification. */
    static long timeTls(String host, int port, int timeoutMs) throws IOException {
        long t0 = System.nanoTime();
        SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
        try (SSLSocket s = (SSLSocket) f.createSocket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.startHandshake();          // the round trips happen here
            return ms(t0);
        }
    }

    /**
     * THE TABLE THIS LAB EXISTS FOR.
     *
     * Two failures that look identical in a log ("could not reach the service")
     * can have opposite causes and opposite correct responses. The distinguishing
     * signal is almost always *how long it took to fail* and *which exception
     * type* came back.
     */
    static Diagnosis diagnose(Exception e, long elapsedMs, int timeoutMs) {
        if (e instanceof UnknownHostException) {
            return new Diagnosis("Names (DNS)",
                    "The name could not be turned into an address at all. The service may be fine; you may have a typo, a missing search domain, or a resolver that is down.",
                    "Retrying the same name rarely helps. Check the name first.");
        }
        if (e instanceof SSLHandshakeException) {
            return new Diagnosis("Trust (TLS)",
                    "TCP connected — the server is reachable and listening. The two sides could not agree on identity: an expired or wrong-host certificate, or a private CA your JVM does not trust.",
                    "Never. This will fail identically forever until a certificate or trust store changes.");
        }
        if (e instanceof ConnectException) {
            // Fast + ConnectException = the host actively said "no".
            return new Diagnosis("Connection (TCP) — REFUSED",
                    "The host was reached and it answered immediately with a refusal (a TCP RST). Something is *there*, but nothing is listening on that port. Wrong port, or the process is down.",
                    "Retrying helps only if the process is restarting. It will keep failing fast, which is at least cheap.");
        }
        if (e instanceof SocketTimeoutException) {
            boolean atLimit = elapsedMs >= timeoutMs * 0.9;
            return new Diagnosis("Connection or read — SILENCE",
                    atLimit
                        ? "Nothing came back at all before the timeout. Silence, not refusal: packets are being dropped rather than answered. Classic causes are a firewall or security group with a DROP rule, a wrong IP that routes nowhere, or a server that accepted the connection and then hung."
                        : "The socket timed out early, which usually means the timeout you set is smaller than the server's normal response time.",
                    "Only if you believe the cause is transient. If it is a firewall rule, every retry costs a full timeout and buys nothing.");
        }
        if (e instanceof SocketException se && String.valueOf(se.getMessage()).contains("reset")) {
            return new Diagnosis("Connection (TCP) — RESET MID-STREAM",
                    "The connection was established and working, then torn down. Typically an idle timeout on a NAT box or load balancer, or the server process dying mid-request.",
                    "Usually yes, on a FRESH connection — but only if the request is idempotent, because you cannot know whether the server processed it.");
        }
        if (e instanceof EOFException) {
            return new Diagnosis("Connection (TCP) — CLOSED EARLY",
                    "The peer closed cleanly before sending a complete answer.",
                    "Same rule as a reset: fresh connection, and only for idempotent work.");
        }
        return new Diagnosis("Unclassified", e.toString(), "Unknown — read the stack trace.");
    }

    /** Run an action, catch whatever it throws, and print the diagnosis. */
    static void attempt(String label, int timeoutMs, ThrowingRunnable action) {
        long t0 = System.nanoTime();
        try {
            action.run();
            System.out.printf("  %-34s succeeded in %,d ms%n", label, ms(t0));
        } catch (Exception e) {
            long el = ms(t0);
            Diagnosis d = diagnose(e, el, timeoutMs);
            System.out.printf("  %-34s failed in %,5d ms%n", label, el);
            System.out.printf("      exception : %s%n", e.getClass().getSimpleName());
            System.out.printf("      layer     : %s%n", d.layer());
            System.out.printf("      means     : %s%n", wrap(d.meaning(), 62, 18));
            System.out.printf("      retry?    : %s%n", wrap(d.retry(), 62, 18));
        }
    }

    interface ThrowingRunnable { void run() throws Exception; }

    static String wrap(String s, int width, int indent) {
        StringBuilder sb = new StringBuilder();
        int col = 0;
        for (String w : s.split(" ")) {
            if (col + w.length() > width) { sb.append("\n").append(" ".repeat(indent)); col = 0; }
            sb.append(w).append(' ');
            col += w.length() + 1;
        }
        return sb.toString().stripTrailing();
    }

    private Probe() {}
}
