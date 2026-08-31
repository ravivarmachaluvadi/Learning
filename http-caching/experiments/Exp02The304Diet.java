import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Experiment 02 — the 304 diet: what validation actually saves (and what it doesn't).
 *
 * Proves the claims from README §10.2:
 *   1. A conditional request that gets `304 Not Modified` moves ZERO body bytes
 *      while still renewing the copy's freshness.
 *   2. When the content HAS changed, the same conditional request gets a full
 *      200 with a new ETag — the client never has to guess which case it's in.
 *   3. Validation still costs a full round trip. Freshness costs nothing at all.
 *      (That distinction is the whole reason `max-age` exists alongside ETags.)
 *
 * Run:  java experiments/Exp02The304Diet.java
 */
public class Exp02The304Diet {

    static final long NETWORK_DELAY_MILLIS = 50;   // 50 ms one-way-ish, so round trips are visible

    static final int ITEM_COUNT = 800;             // padded so the body is realistically chunky
    static int biryaniPrice = 250;

    static String baseUrl;

    static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /** The menu, serialized exactly as it goes on the wire. */
    static byte[] currentBody() {
        StringBuilder sb = new StringBuilder()
                .append("{\"restaurant\": \"Biryani House\", \"biryani\": ").append(biryaniPrice)
                .append(", \"items\": [");
        for (int i = 0; i < ITEM_COUNT; i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"dosa\"");
        }
        return sb.append("]}").toString().getBytes(StandardCharsets.UTF_8);
    }

    static String currentEtag() {
        // An ETag is just a fingerprint of the content: same bytes -> same tag.
        // This is the "version: dinner-v2" chalk mark from README §8.
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(currentBody());
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) hex.append(String.format("%02x", digest[i]));
            return "\"" + hex + "\"";
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------ origin

    static void origin(HttpExchange ex) throws IOException {
        sleep(NETWORK_DELAY_MILLIS);
        byte[] body = currentBody();
        String etag = currentEtag();

        // ---- THE validation check (§10.2). The client told us which version
        // ---- it already holds; if it still matches, we send NO BODY at all.
        if (etag.equals(ex.getRequestHeaders().getFirst("If-None-Match"))) {
            ex.getResponseHeaders().set("ETag", etag);
            ex.getResponseHeaders().set("Cache-Control", "max-age=300");
            ex.sendResponseHeaders(304, -1);         // -1 = not even a Content-Length
            ex.close();
            return;
        }

        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("ETag", etag);            // the validator travels
        ex.getResponseHeaders().set("Cache-Control", "max-age=300");  // with the contract
        ex.sendResponseHeaders(200, body.length);
        try (var out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    // ------------------------------------------------------------------ client

    /** status, body bytes, header bytes, etag, elapsed ms. */
    record Result(int status, int bodyBytes, int headerBytes, String etag, double ms) { }

    static Result request(String ifNoneMatch) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + "/menu"));
        if (ifNoneMatch != null) req.header("If-None-Match", ifNoneMatch);
        long t0 = System.nanoTime();
        // Java's HttpClient hands a 304 back like any other response: status 304
        // and an empty body — which is precisely what a 304 carries on the wire.
        HttpResponse<byte[]> r = CLIENT.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
        double ms = (System.nanoTime() - t0) / 1e6;
        return new Result(r.statusCode(), r.body().length, headerBytes(r),
                r.headers().firstValue("ETag").orElse(null), ms);
    }

    /** Weigh the response's header block exactly as it travelled: "Name: value\r\n" … blank line. */
    static int headerBytes(HttpResponse<?> r) {
        int n = 0;
        for (Map.Entry<String, List<String>> h : r.headers().map().entrySet()) {
            for (String v : h.getValue()) {
                n += (h.getKey() + ": " + v + "\r\n").length();
            }
        }
        return n + 2;                                // the blank line ending the header block
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------- main

    public static void main(String[] args) throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", Exp02The304Diet::origin);
        srv.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        srv.start();
        baseUrl = "http://127.0.0.1:" + srv.getAddress().getPort();

        System.out.println("STEP 1 — cold fetch: the cache has nothing, so it asks for everything");
        Result cold = request(null);
        System.out.printf("  %d OK    body %6d bytes + headers %3d   %5.1f ms%n",
                cold.status(), cold.bodyBytes(), cold.headerBytes(), cold.ms());
        System.out.printf("  stored alongside the body: ETag %s%n%n", cold.etag());
        int coldTotal = cold.bodyBytes() + cold.headerBytes();

        System.out.println("STEP 2 — copy went stale, content UNCHANGED: revalidate");
        Result warm = request(cold.etag());
        System.out.printf("  → sent  If-None-Match: %s%n", cold.etag());
        System.out.printf("  %d Not Modified   body %6d bytes + headers %3d   %5.1f ms%n",
                warm.status(), warm.bodyBytes(), warm.headerBytes(), warm.ms());
        int warmTotal = warm.bodyBytes() + warm.headerBytes();
        System.out.println("  the cache keeps the body it already had; its age clock resets.\n");

        System.out.println("STEP 3 — the kitchen raises the price; SAME conditional request");
        biryaniPrice = 280;
        Result changed = request(cold.etag());
        System.out.printf("  → sent  If-None-Match: %s   (now out of date)%n", cold.etag());
        System.out.printf("  %d OK    body %6d bytes + headers %3d   %5.1f ms%n",
                changed.status(), changed.bodyBytes(), changed.headerBytes(), changed.ms());
        System.out.printf("  new validator: ETag %s%n%n", changed.etag());

        System.out.println("SCOREBOARD");
        System.out.println("-".repeat(72));
        System.out.printf("  full response ..... %6d bytes%n", coldTotal);
        System.out.printf("  304 revalidation .. %6d bytes   (%.1f%% of a full response)%n",
                warmTotal, 100.0 * warmTotal / coldTotal);
        System.out.printf("  bytes saved ....... %6d   (%.1f%% saved)%n",
                coldTotal - warmTotal, 100.0 * (1 - (double) warmTotal / coldTotal));
        System.out.printf("  round trips ....... 1 either way  (~%.0f ms — NOT saved)%n",
                changed.ms());

        System.out.println("\nWHAT YOU JUST SAW");
        System.out.println("-".repeat(72));
        System.out.println("""
                Validation is a byte diet, not a latency cure: a 304 carries no body,
                but the client still waited a full round trip to learn that. That is
                exactly why `max-age` exists alongside ETags — while a copy is FRESH,
                no request happens at all (0 bytes, 0 ms). Freshness saves the trip;
                validation saves the payload once the trip is unavoidable.
                Step 3's lesson: the client sends the identical conditional request
                in both cases. It never has to know whether the content changed —
                the origin decides, and answers 304 or 200 accordingly. (§10.2)""");
        srv.stop(0);
    }
}
