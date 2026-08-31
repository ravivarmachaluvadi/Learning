import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Experiment 02 — round trips, not bytes, dominate latency.
 *
 * Proves the claim from README §10.4: a screen built from several DEPENDENT
 * REST calls pays one network round trip per call, so an aggregated single
 * call (the GraphQL idea) wins by roughly the number of calls — even though
 * it transfers the same data.
 *
 * The server sleeps 30 ms per request to simulate realistic client↔server
 * network latency (a phone on 4G is often 50–300 ms; we're being kind).
 *
 * Run:  java experiments/Exp02RoundTrips.java
 */
public class Exp02RoundTrips {

    static final double SIMULATED_RTT = 0.030; // seconds of network latency per request

    static final Map<String, String> DATA = Map.of(
            "/me",             "{\"id\": 1, \"name\": \"Ravi\", \"active_order\": 97}",
            "/orders/97",      "{\"id\": 97, \"status\": \"COOKING\", \"restaurant\": 42, \"rider\": 7}",
            "/restaurants/42", "{\"id\": 42, \"name\": \"Biryani House\"}",
            "/riders/7",       "{\"id\": 7, \"lat\": 18.52, \"lng\": 73.85}");

    static int port;
    // HTTP/1.1 on purpose: one request at a time per connection, like the REST
    // baseline README §10.2 describes. HTTP/2 multiplexing is the *fix*, not the setup.
    static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    // ------------------------------------------------------------------ server

    static HttpServer startServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            sleepSeconds(SIMULATED_RTT); // the network commute — paid PER REQUEST
            exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath();
            String payload;
            if (path.equals("/home-screen")) {
                // The aggregated "one query, exactly the screen's shape" endpoint.
                payload = "{\"me\": " + DATA.get("/me")
                        + ", \"activeOrder\": " + merge(DATA.get("/orders/97"),
                              "\"restaurant\": " + DATA.get("/restaurants/42")
                            + ", \"rider\": " + DATA.get("/riders/7"))
                        + "}";
            } else {
                payload = DATA.getOrDefault(path, "{}");
            }
            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
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

    // ------------------------------------------------------------------ client

    static String get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .GET().build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    /** 4 calls; each NEEDS the previous response, so none can run in parallel. */
    static void restChatty() throws IOException, InterruptedException {
        String me = get("/me");                                       // round trip 1
        String order = get("/orders/" + field(me, "active_order"));    // round trip 2 (needs #1)
        get("/restaurants/" + field(order, "restaurant"));             // round trip 3 (needs #2)
        get("/riders/" + field(order, "rider"));                       // round trip 4 (needs #2)
    }

    /** One round trip carrying exactly the screen's shape. */
    static void aggregated() throws IOException, InterruptedException {
        get("/home-screen");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Minimal JSON field reader — enough for these fixed payloads, and it keeps
     * the lab dependency-free. The point is only that call N+1's URL is built
     * from call N's body: that is what makes the calls DEPENDENT.
     */
    static String field(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*([^,}\\s]+)").matcher(json);
        if (!m.find()) throw new IllegalStateException("no field " + key + " in " + json);
        return m.group(1).replace("\"", "");
    }

    /** Splice extra members into an existing JSON object literal. */
    static String merge(String json, String extra) {
        return json.substring(0, json.lastIndexOf('}')) + ", " + extra + "}";
    }

    static void sleepSeconds(double seconds) {
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = startServer();

        // Warm-up, not part of the measurement: the FIRST HTTP call in a JVM pays
        // class loading and JIT costs that have nothing to do with the network.
        // A real app has paid them long before the user opens the home screen.
        restChatty();
        aggregated();

        long t0 = System.nanoTime();
        restChatty();
        double chattyMs = (System.nanoTime() - t0) / 1_000_000.0;

        t0 = System.nanoTime();
        aggregated();
        double aggMs = (System.nanoTime() - t0) / 1_000_000.0;

        server.stop(0);

        System.out.println("Home screen needs: me + active order + restaurant + rider location");
        System.out.printf("Simulated network latency: %.0f ms per request%n%n", SIMULATED_RTT * 1000);
        System.out.printf("  4 dependent REST calls : %7.1f ms%n", chattyMs);
        System.out.printf("  1 aggregated call      : %7.1f ms%n", aggMs);
        System.out.printf("  ratio                  : %7.1fx%n%n", chattyMs / aggMs);
        System.out.println("WHAT YOU JUST SAW");
        System.out.println("-".repeat(72));
        System.out.println("Same data either way — the difference is purely the number of round");
        System.out.println("trips. Dependent calls serialize, so latency multiplies by call count.");
        System.out.println("This measured gap IS the argument for GraphQL / aggregated endpoints /");
        System.out.println("HTTP/2 multiplexing on chatty mobile screens. (README §7 'round trip',");
        System.out.println("§10.4). At a real 200 ms mobile RTT, multiply everything by ~7.");
        System.exit(0);
    }
}
