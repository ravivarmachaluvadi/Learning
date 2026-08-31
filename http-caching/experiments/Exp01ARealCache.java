import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Experiment 01 — a real, rule-following caching proxy in ~60 lines.
 *
 * Proves three claims from README §10.1 and §11.1:
 *   1. A cache turns N identical requests into 1 origin request (hit ratio).
 *   2. Hits skip the network commute entirely — latency collapses.
 *   3. A fresh copy serves the OLD data after the origin changes — and that is
 *      the cache honoring the contract, not a bug.
 *
 * Run:  java experiments/Exp01ARealCache.java
 */
public class Exp01ARealCache {

    static final int MAX_AGE = 60;
    static final long ORIGIN_DELAY_MILLIS = 200;   // the origin is "far away" (README: RTT dominates)

    static int price = 250;                        // the origin's truth — mutable
    static final AtomicInteger originHits = new AtomicInteger();

    static String originBase;                      // filled in once the origin picks its port

    // HTTP/1.1 on purpose: com.sun.net.httpserver speaks 1.1, and 1.1 is the wire
    // format you hand-typed in the protocols lab — headers stay readable.
    static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    // ------------------------------------------------------------------ origin

    /** The authoritative server. Slow, and it attaches the reuse contract itself. */
    static void origin(HttpExchange ex) throws IOException {
        originHits.incrementAndGet();
        sleep(ORIGIN_DELAY_MILLIS);
        byte[] body = ("{\"restaurant\": \"Biryani House\", \"biryani\": " + price + "}")
                .getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        // ---- the reuse contract, attached to the response itself (§10.1) ----
        ex.getResponseHeaders().set("Cache-Control", "max-age=" + MAX_AGE);
        ex.sendResponseHeaders(200, body.length);   // Content-Length comes from this argument
        try (var out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    // ------------------------------------------------------------- caching proxy

    /** One stored response: the body, when we stored it, and the lifetime we were granted. */
    record Entry(byte[] body, long storedAtMillis, int maxAge) { }

    static final Map<String, Entry> STORE = new HashMap<>();   // cache key (path) -> entry

    static void proxy(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        Entry entry = STORE.get(path);
        if (entry != null) {
            double age = (System.currentTimeMillis() - entry.storedAtMillis()) / 1000.0;
            if (age < entry.maxAge()) {
                // ---- THE freshness check: age < max-age  →  serve without
                // ---- contacting the origin. This one comparison is HTTP
                // ---- caching's entire performance story. (§10.1)
                reply(ex, entry.body(), true, age);
                return;
            }
        }
        // miss (or stale with no validator — this proxy has no ETag support yet)
        HttpResponse<byte[]> r = get(originBase + path);
        byte[] body = r.body();
        String cc = r.headers().firstValue("Cache-Control").orElse("");
        int maxAge = cc.contains("max-age=")
                ? Integer.parseInt(cc.split("max-age=")[1].split(",")[0].trim())
                : 0;
        // obey the contract: store only what the origin allowed us to store
        if (maxAge > 0) {
            STORE.put(path, new Entry(body, System.currentTimeMillis(), maxAge));
        }
        reply(ex, body, false, 0);
    }

    static void reply(HttpExchange ex, byte[] body, boolean hit, double age) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("X-Cache", hit ? "HIT" : "MISS");    // CDN debug style (§11.6)
        ex.getResponseHeaders().set("Age", String.valueOf((int) age));   // how old is this copy
        ex.sendResponseHeaders(200, body.length);
        try (var out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    // ----------------------------------------------------------------- client

    /** One fetch, with everything the narration needs to see. */
    record Fetched(int biryani, String xCache, String age, double ms) { }

    static Fetched fetch(String base) throws Exception {
        long t0 = System.nanoTime();
        HttpResponse<byte[]> r = get(base + "/menu");
        double ms = (System.nanoTime() - t0) / 1e6;
        return new Fetched(jsonInt(new String(r.body(), StandardCharsets.UTF_8), "biryani"),
                r.headers().firstValue("X-Cache").orElse("—"),
                r.headers().firstValue("Age").orElse("0"),
                ms);
    }

    static HttpResponse<byte[]> get(String url) {
        try {
            return CLIENT.send(HttpRequest.newBuilder(URI.create(url)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Just enough JSON to read {"biryani": 250} — no library, this is a stdlib-only lab. */
    static int jsonInt(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------- main

    public static void main(String[] args) throws Exception {
        // Port 0 = "any free port": nothing here touches the public internet.
        HttpServer originServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        originServer.createContext("/", Exp01ARealCache::origin);
        originServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        originServer.start();
        originBase = "http://127.0.0.1:" + originServer.getAddress().getPort();

        HttpServer proxyServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxyServer.createContext("/", Exp01ARealCache::proxy);
        proxyServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        proxyServer.start();
        String proxyBase = "http://127.0.0.1:" + proxyServer.getAddress().getPort();

        System.out.println("PHASE A — 5 users straight to the origin (no cache)");
        long t0 = System.nanoTime();
        originHits.set(0);
        for (int i = 0; i < 5; i++) {
            Fetched f = fetch(originBase);
            System.out.printf("  user %d: biryani=₹%d  %6.0f ms%n", i + 1, f.biryani(), f.ms());
        }
        System.out.printf("  => origin answered %d times, total %.1f s%n%n",
                originHits.get(), (System.nanoTime() - t0) / 1e9);

        System.out.printf("PHASE B — 5 users through the caching proxy (Cache-Control: max-age=%d)%n",
                MAX_AGE);
        System.out.println("  (spaced ~1 s apart so you can watch the copy's Age clock climb)");
        t0 = System.nanoTime();
        originHits.set(0);
        for (int i = 0; i < 5; i++) {
            Fetched f = fetch(proxyBase);
            System.out.printf("  user %d: biryani=₹%d  %6.0f ms   X-Cache: %-4s Age: %ss  (fresh for %d more s)%n",
                    i + 1, f.biryani(), f.ms(), f.xCache(), f.age(),
                    MAX_AGE - Integer.parseInt(f.age()));
            sleep(1050);
        }
        System.out.printf("  => origin answered %d time(s) for 5 users%n%n", originHits.get());

        System.out.println("PHASE C — the origin raises the price to ₹280 …");
        price = 280;
        sleep(300);
        Fetched f = fetch(proxyBase);
        int truth = jsonInt(new String(get(originBase + "/menu").body(), StandardCharsets.UTF_8),
                "biryani");
        System.out.printf("  origin's truth is now : ₹%d%n", truth);
        System.out.printf("  proxy serves          : ₹%d   X-Cache: %s, Age: %ss%n",
                f.biryani(), f.xCache(), f.age());
        System.out.printf("  …and will keep serving ₹250 until the copy's age reaches %d s.%n%n",
                MAX_AGE);

        System.out.println("WHAT YOU JUST SAW");
        System.out.println("-".repeat(72));
        System.out.println("""
                B: one MISS filled the cache; four HITs never touched the origin and
                   returned ~200 ms faster — hit ratio 80% from five requests.
                C: the stale price is NOT a malfunction. max-age=60 was a promise —
                   'reuse freely for 60 s' — and the proxy is keeping it. Staleness
                   is a declared consistency window (README §12), and the origin has
                   no way to recall the copy early (§11.1). Choose max-age like the
                   promise it is.""");

        originServer.stop(0);
        proxyServer.stop(0);
    }
}
