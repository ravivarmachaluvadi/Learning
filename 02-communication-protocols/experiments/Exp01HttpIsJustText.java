import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Experiment 01 — HTTP is just text over a TCP socket (and framing is real).
 *
 * Proves two claims from README §4 and §10.2:
 *   1. An HTTP request is literally text you could type; any program that can
 *      write bytes to a socket can speak it.
 *   2. A socket is a byte STREAM, not a message pipe: one read() call has no
 *      relationship to one message. The receiver must find message boundaries
 *      itself — HTTP does it with the blank line + Content-Length.
 *
 * Run:  java experiments/Exp01HttpIsJustText.java
 */
public class Exp01HttpIsJustText {

    /** Part 2 reads the stream in gulps this small so boundaries visibly don't align. */
    static final int GULP_BYTES = 120;

    static final String MENU =
            "{\"restaurant\": \"Biryani House\", \"items\": [{\"dosa\": 120}, {\"biryani\": 250}]}";
    static final String NOTICE = "{\"notice\": \"closing soon\"}";

    static int port;

    // ------------------------------------------------------------------ server

    /**
     * A real HTTP/1.1 server from the JDK — keep-alive: one connection, many requests.
     * Nothing about it is special; it exists only so the raw socket below has
     * something honest to talk to.
     */
    static HttpServer startServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes(); // drain, or keep-alive desyncs
            byte[] body = (exchange.getRequestURI().getPath().equals("/menu") ? MENU : NOTICE)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            // The length argument IS the framing promise: exactly this many body bytes follow.
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        port = server.getAddress().getPort();
        return server;
    }

    // ------------------------------------------------------------------ part 1

    static void part1HandwrittenHttp() throws IOException {
        System.out.println("=".repeat(72));
        System.out.println("PART 1 — speak HTTP with a raw socket, no library");
        System.out.println("=".repeat(72));

        // A raw OS socket: the same primitive from README §3.
        Socket sock = new Socket("127.0.0.1", port);

        String request =
                "GET /menu HTTP/1.1\r\n"
              + "Host: 127.0.0.1\r\n"
              + "Connection: close\r\n"
              + "\r\n";                    // blank line = "headers done" — HTTP's framing

        System.out.println("\n--- bytes we WRITE into the socket (hand-typed HTTP) ---");
        System.out.println(request.replace("\r\n", "\\r\\n\n"));
        sock.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
        sock.getOutputStream().flush();

        System.out.println("--- bytes the server writes BACK (read until it closes) ---");
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        InputStream in = sock.getInputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            raw.write(buf, 0, n);
        }
        System.out.println(raw.toString(StandardCharsets.UTF_8));
        sock.close();
        System.out.println("No HTTP library was involved. HTTP is text + discipline over TCP.\n");
    }

    // ------------------------------------------------------------------ part 2

    static void part2Framing() throws IOException {
        System.out.println("=".repeat(72));
        System.out.println("PART 2 — one read() is NOT one message (the framing problem, live)");
        System.out.println("=".repeat(72));

        Socket sock = new Socket("127.0.0.1", port);
        // Pipeline TWO requests in a single write. Two logical exchanges, one stream.
        String twoRequests =
                "GET /menu HTTP/1.1\r\nHost: x\r\n\r\n"
              + "GET /notice HTTP/1.1\r\nHost: x\r\n\r\n";
        sock.getOutputStream().write(twoRequests.getBytes(StandardCharsets.UTF_8));
        sock.getOutputStream().flush();

        // Read the stream in small gulps to show boundaries don't align with read().
        System.out.println("\nEach line below is what ONE read() into a "
                + GULP_BYTES + "-byte buffer returned:");
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        sock.setSoTimeout(2000);
        byte[] buf = new byte[GULP_BYTES];
        try {
            while (true) {
                int n = sock.getInputStream().read(buf);
                if (n <= 0) break;
                System.out.printf("  read -> %3d bytes: %s...%n",
                        n, repr(buf, Math.min(n, 60)));
                stream.write(buf, 0, n);
                // Latin-1 keeps one byte == one char, so indexes below are byte indexes.
                String sofar = stream.toString(StandardCharsets.ISO_8859_1);
                if (count(sofar, "\r\n\r\n") >= 2 && sofar.endsWith("}")) break;
            }
        } catch (SocketTimeoutException ignored) {
            // nothing more is coming; what we have is what we parse
        }
        sock.close();

        System.out.println("\nTwo responses arrived GLUED into one byte stream. To split them we");
        System.out.println("do what every HTTP library does: find the blank line, read the");
        System.out.println("Content-Length header, count exactly that many body bytes:\n");

        String rest = stream.toString(StandardCharsets.ISO_8859_1);
        for (int i = 1; i <= 2; i++) {
            int split = rest.indexOf("\r\n\r\n");
            String head = rest.substring(0, split);
            rest = rest.substring(split + 4);
            int length = contentLength(head);
            String body = rest.substring(0, length);
            rest = rest.substring(length);
            System.out.printf("  message %d: Content-Length=%3d -> body %s%n", i, length, body);
        }

        System.out.println("\nWHAT YOU JUST SAW");
        System.out.println("-".repeat(72));
        System.out.println("TCP delivered every byte, in order — and told us nothing about where");
        System.out.println("one message ends. Framing (blank line + Content-Length) is the FIRST");
        System.out.println("thing every protocol in this lab defines. (README §4, §10.2)");
    }

    // ------------------------------------------------------------------ helpers

    /** Pull Content-Length out of a header block. Header names are case-insensitive. */
    static int contentLength(String head) {
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length")) {
                return Integer.parseInt(line.split(":")[1].trim());
            }
        }
        throw new IllegalStateException("no Content-Length in: " + head);
    }

    /** Render raw bytes readably, with the invisible framing characters made visible. */
    static String repr(byte[] b, int len) {
        String s = new String(b, 0, len, StandardCharsets.ISO_8859_1);
        return "\"" + s.replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }

    static int count(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = startServer();
        try {
            part1HandwrittenHttp();
            part2Framing();
        } finally {
            server.stop(0);
        }
        System.exit(0);
    }
}
