import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/**
 * Experiment 03 — polling vs push (SSE), racing to detect one event.
 *
 * Proves the claim from README §10.6–10.7: pull pays twice (wasted requests AND
 * detection delay); push pays neither. One server-side event fires at a random
 * moment; a 1-second poller and an SSE stream race to notice it.
 *
 * The SSE client is a RAW SOCKET reading `data:` lines, so you also see the
 * real wire format of a Server-Sent-Events stream (README §10.7).
 *
 * Run:  java experiments/Exp03PollVsPush.java
 */
public class Exp03PollVsPush {

    static final double POLL_INTERVAL = 1.0;

    static volatile long eventFiredAt;             // System.nanoTime() the order status changed
    static final CountDownLatch EVENT = new CountDownLatch(1); // the server-side "news happened" signal

    static int port;
    static final Map<String, Object> POLL_RESULT = new ConcurrentHashMap<>();
    static final Map<String, Object> SSE_RESULT = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ server

    static HttpServer startServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/status", exchange -> {            // ---- the PULL endpoint
            exchange.getRequestBody().readAllBytes();
            byte[] body = ("{\"status\": \"" + (EVENT.getCount() == 0 ? "DELIVERED" : "COOKING") + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        server.createContext("/events", exchange -> {            // ---- the PUSH endpoint
            exchange.getRequestBody().readAllBytes();
            // An SSE response: normal HTTP that simply never finishes.
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0); // 0 = "body of unknown length, streamed"
            OutputStream out = exchange.getResponseBody();
            out.flush();
            try {
                EVENT.await();                                   // block until news exists
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            // The instant there is news, write it. No client request involved.
            out.write("data: {\"status\": \"DELIVERED\"}\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        });

        // Every handler needs its own thread: the SSE handler PARKS for seconds,
        // and with the default (null) executor it would park the whole server —
        // the poller would never get an answer.
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        port = server.getAddress().getPort();
        return server;
    }

    // ------------------------------------------------------------------ clients

    /** Pull: ask every POLL_INTERVAL seconds until the answer changes. */
    static void poller() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        int requests = 0;
        while (true) {
            requests++;
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/status")).GET().build();
            String status = client.send(req, HttpResponse.BodyHandlers.ofString()).body();
            if (status.contains("DELIVERED")) {
                POLL_RESULT.put("latency", (System.nanoTime() - eventFiredAt) / 1e9);
                POLL_RESULT.put("requests", requests);
                return;
            }
            Thread.sleep((long) (POLL_INTERVAL * 1000));
        }
    }

    /** Push: one request, then just listen. Raw socket so the wire is visible. */
    static void sseListener(CountDownLatch ready) throws IOException {
        Socket sock = new Socket("127.0.0.1", port);
        // HTTP/1.0 on purpose. Under HTTP/1.1 the JDK server would frame an
        // unknown-length body with chunked transfer encoding, and the "wire bytes"
        // printed below would be chunk headers wrapped around the event instead of
        // the pure SSE format README §10.7 shows. HTTP/1.0 + connection-close is
        // HTTP's other legal framing rule, and it leaves the event bytes untouched.
        sock.getOutputStream().write("GET /events HTTP/1.0\r\nHost: x\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8));
        sock.getOutputStream().flush();

        InputStream in = sock.getInputStream();
        StringBuilder buf = new StringBuilder();
        byte[] chunk = new byte[1024];
        while (buf.indexOf("\r\n\r\n") < 0) {          // consume the response headers
            int n = in.read(chunk);
            if (n < 0) break;
            buf.append(new String(chunk, 0, n, StandardCharsets.ISO_8859_1));
        }
        ready.countDown();                             // stream is open; race may start
        while (!body(buf).contains("\n\n")) {
            int n = in.read(chunk);                    // block here until the server pushes
            if (n < 0) break;
            buf.append(new String(chunk, 0, n, StandardCharsets.ISO_8859_1));
        }
        SSE_RESULT.put("latency", (System.nanoTime() - eventFiredAt) / 1e9);
        SSE_RESULT.put("wire", body(buf).strip());
        sock.close();
    }

    static String body(StringBuilder buf) {
        int i = buf.indexOf("\r\n\r\n");
        return i < 0 ? "" : buf.substring(i + 4);
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        HttpServer server = startServer();

        CountDownLatch sseReady = new CountDownLatch(1);
        Thread sse = new Thread(() -> {
            try { sseListener(sseReady); } catch (IOException e) { e.printStackTrace(); }
        });
        sse.setDaemon(true);
        sse.start();
        sseReady.await();

        Thread poll = new Thread(() -> {
            try { poller(); } catch (Exception e) { e.printStackTrace(); }
        });
        poll.setDaemon(true);
        poll.start();

        double delay = 2.0 + new Random().nextDouble() * 3.0;
        System.out.printf("Order placed. Kitchen will finish at a random moment (~%.1fs)…%n", delay);
        Thread.sleep((long) (delay * 1000));
        eventFiredAt = System.nanoTime();
        EVENT.countDown();                             // <-- the news happens, exactly once

        poll.join(10_000);
        Thread.sleep(200);

        System.out.printf("%n  poller  (ask every %.0fs): %d requests, noticed after %7.1f ms%n",
                POLL_INTERVAL, (int) POLL_RESULT.get("requests"),
                (double) POLL_RESULT.get("latency") * 1000);
        System.out.printf("  SSE push (1 request)   : 1 request,  noticed after %7.1f ms%n",
                (double) SSE_RESULT.get("latency") * 1000);
        System.out.printf("%n  bytes the SSE client actually received on the wire:%n");
        System.out.printf("      '%s'%n", SSE_RESULT.get("wire"));

        System.out.println("\nWHAT YOU JUST SAW");
        System.out.println("-".repeat(72));
        System.out.println("The poller paid TWICE: every no-news request was wasted work, and it");
        System.out.println("still noticed late (on average, half the poll interval). The push");
        System.out.println("channel spent one request and noticed in ~network time. Multiply the");
        System.out.println("poller by 10,000 customers to get README §10.6's prediction. SSE's");
        System.out.println("wire format is just 'data:' lines in a never-ending HTTP response.");
        server.stop(0);
        System.exit(0);
    }
}
