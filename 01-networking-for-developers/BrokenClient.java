import java.io.*;
import java.net.*;

/**
 * BrokenClient — for EXERCISES.md, rung 6 (Debug).
 *
 * This client was written by someone who read the docs, set a timeout, and
 * reasonably believed the call was now bounded. It has ONE bug.
 *
 * Run:  java BrokenClient.java
 *
 * Two checks run below. One passes, one fails. Your job:
 *   1. Which check fails, and what does the failure actually look like — an
 *      exception, or something else?
 *   2. Which single line in `call()` is wrong, and what is missing?
 *   3. The author set a timeout of 1500 ms. Explain precisely what those
 *      1500 ms bound, and what they do NOT bound.
 *   4. In a Spring Boot service handling requests on a bounded thread pool,
 *      describe what happens to the WHOLE SERVICE when one downstream
 *      dependency starts behaving like the server in check 2.
 *
 * Answers in SOLUTIONS.md.
 */
public class BrokenClient {

    static final int TIMEOUT_MS = 1500;

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(66));
        System.out.println("BrokenClient — one bug, two checks");
        System.out.println("=".repeat(66));
        System.out.printf("%nThe author's intent: \"no call to this service may take more%n"
                        + "than %d ms.\" Both checks test exactly that claim.%n", TIMEOUT_MS);

        // CHECK 1 — a host that never answers the handshake.
        System.out.printf("%nCHECK 1  unreachable_host_fails_within_budget%n");
        long t0 = System.nanoTime();
        String r1;
        try { r1 = call("10.255.255.1", 443, "ping"); }
        catch (Exception e) { r1 = e.getClass().getSimpleName(); }
        long e1 = (System.nanoTime() - t0) / 1_000_000;
        boolean c1 = e1 <= TIMEOUT_MS * 1.5;
        System.out.printf("         outcome  : %s after %,d ms%n", r1, e1);
        System.out.printf("         budget   : %d ms%n", TIMEOUT_MS);
        System.out.printf("         -> %s%n", c1 ? "PASS" : "FAIL");

        // CHECK 2 — a server that accepts the connection and then says nothing.
        // This is a healthy TCP connection to a wedged application.
        System.out.printf("%nCHECK 2  silent_server_fails_within_budget%n");
        try (Server s = new Server(Server.Mode.SILENT, 0)) {
            long t1 = System.nanoTime();
            final String[] out = { "still running" };
            Thread worker = Thread.ofVirtual().start(() -> {
                try { out[0] = call("127.0.0.1", s.port(), "ping"); }
                catch (Exception e) { out[0] = e.getClass().getSimpleName(); }
            });
            // Watchdog, so this program terminates instead of demonstrating the
            // bug by hanging your terminal.
            worker.join(java.time.Duration.ofMillis(5000));
            long e2 = (System.nanoTime() - t1) / 1_000_000;
            boolean hung = worker.isAlive();
            boolean c2 = !hung && e2 <= TIMEOUT_MS * 1.5;
            System.out.printf("         outcome  : %s after %,d ms%n",
                    hung ? "STILL BLOCKED — no exception, no return" : out[0], e2);
            System.out.printf("         budget   : %d ms%n", TIMEOUT_MS);
            System.out.printf("         -> %s%s%n", c2 ? "PASS" : "FAIL",
                    hung ? "  (watchdog gave up at 5,000 ms; the real call has no upper bound)" : "");
            worker.interrupt();
        }

        System.out.println("\nNow read call() below. The bug is one missing line.");
    }

    /**
     * Send one line, read one line back.
     */
    static String call(String host, int port, String message) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);

            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            out.println(message);
            return in.readLine();
        }
    }
}
