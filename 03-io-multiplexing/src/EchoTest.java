import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Tests that double as executable documentation.
 *
 *   java src/EchoTest.java           run against the correct SelectorEchoServer
 *   java src/EchoTest.java broken    run against BrokenSelectorServer (exercise 6)
 *
 * Each test name is a scenario from README.md.
 */
public class EchoTest {

    /** The small surface every server under test shares. */
    interface ServerUnderTest {
        int port() throws IOException;
        long loopIterations();
        void stop() throws IOException;
    }

    static ServerUnderTest correct() throws IOException {
        SelectorEchoServer s = new SelectorEchoServer();
        s.start(0);
        return new ServerUnderTest() {
            public int port() throws IOException { return s.port(); }
            public long loopIterations() { return s.loopIterations.get(); }
            public void stop() throws IOException { s.stop(); }
        };
    }

    static ServerUnderTest broken() throws IOException {
        BrokenSelectorServer s = new BrokenSelectorServer();
        s.start(0);
        return new ServerUnderTest() {
            public int port() throws IOException { return s.port(); }
            public long loopIterations() { return s.loopIterations.get(); }
            public void stop() throws IOException { s.stop(); }
        };
    }

    static int passed = 0, failed = 0;
    static boolean useBroken = false;

    public static void main(String[] args) throws Exception {
        useBroken = args.length > 0 && args[0].equals("broken");
        System.out.println("Running EchoTest against "
                + (useBroken ? "BrokenSelectorServer (expect a failure)" : "SelectorEchoServer"));
        System.out.println();

        one_thread_echoes_one_client();
        one_thread_serves_many_clients_concurrently();
        each_connection_keeps_its_own_buffer();
        closed_connection_is_deregistered_and_loop_sleeps();
        blocking_server_still_echoes();

        System.out.println();
        System.out.printf("%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    static ServerUnderTest server() throws IOException {
        return useBroken ? broken() : correct();
    }

    // ------------------------------------------------------------------ tests

    /** The happy path: bytes in, same bytes out, from a single-threaded server. */
    static void one_thread_echoes_one_client() throws Exception {
        ServerUnderTest server = server();
        try (SocketChannel client = connect(server.port())) {
            assertEquals("hello selector", roundTrip(client, "hello selector"), "one_thread_echoes_one_client");
        } finally {
            server.stop();
        }
    }

    /** 200 clients at once against ONE server thread — the whole point of multiplexing. */
    static void one_thread_serves_many_clients_concurrently() throws Exception {
        ServerUnderTest server = server();
        int clients = 200;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                int id = i;
                results.add(pool.submit(() -> {
                    try (SocketChannel c = connect(server.port())) {
                        String msg = "client-" + id;
                        return msg.equals(roundTrip(c, msg));
                    }
                }));
            }
            int ok = 0;
            for (Future<Boolean> f : results) if (f.get()) ok++;
            assertEquals(clients, ok, "one_thread_serves_many_clients_concurrently");
        } finally {
            server.stop();
        }
    }

    /**
     * Per-connection state lives in the SelectionKey attachment, not on a stack.
     * Interleaving two conversations must not mix their bytes.
     */
    static void each_connection_keeps_its_own_buffer() throws Exception {
        ServerUnderTest server = server();
        try (SocketChannel a = connect(server.port()); SocketChannel b = connect(server.port())) {
            a.write(ByteBuffer.wrap("AAAA".getBytes(StandardCharsets.UTF_8)));
            b.write(ByteBuffer.wrap("BBBB".getBytes(StandardCharsets.UTF_8)));
            String fromA = read(a);
            String fromB = read(b);
            assertEquals("AAAA|BBBB", fromA + "|" + fromB, "each_connection_keeps_its_own_buffer");
        } finally {
            server.stop();
        }
    }

    /**
     * THE PLANTED-BUG TEST (exercise 6).
     * A peer that closes its socket makes the channel permanently readable —
     * reads return -1 forever. A correct loop cancels the key and closes the
     * channel; a loop that just returns gets woken millions of times per second
     * and pins a CPU core at 100%. We assert the loop went back to SLEEP.
     */
    static void closed_connection_is_deregistered_and_loop_sleeps() throws Exception {
        ServerUnderTest server = server();
        try {
            SocketChannel client = connect(server.port());
            roundTrip(client, "bye");
            client.close();               // peer hangs up
            Thread.sleep(100);            // let the server notice
            long before = server.loopIterations();
            Thread.sleep(500);            // half a second of nothing happening
            long wakeups = server.loopIterations() - before;
            assertUnder(wakeups, 50,
                    "closed_connection_is_deregistered_and_loop_sleeps (wakeups in 500 idle ms = " + wakeups + ")");
        } finally {
            server.stop();
        }
    }

    /** The thread-per-connection baseline is correct too — its problem is cost. */
    static void blocking_server_still_echoes() throws Exception {
        BlockingEchoServer server = new BlockingEchoServer();
        server.start(0);
        try (SocketChannel client = connect(server.port())) {
            assertEquals("hi", roundTrip(client, "hi"), "blocking_server_still_echoes");
        } finally {
            server.stop();
        }
    }

    // ---------------------------------------------------------------- helpers

    static SocketChannel connect(int port) throws IOException {
        SocketChannel c = SocketChannel.open(new InetSocketAddress("localhost", port));
        c.socket().setSoTimeout(3000);
        return c;
    }

    static String roundTrip(SocketChannel c, String message) throws IOException {
        c.write(ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)));
        return read(c);
    }

    static String read(SocketChannel c) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(4096);
        int n = c.read(buf);
        if (n <= 0) return "";
        buf.flip();
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static void assertEquals(Object expected, Object actual, String name) {
        if (expected.equals(actual)) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name + "  (expected " + expected + ", got " + actual + ")");
        }
    }

    static void assertUnder(long actual, long limit, String name) {
        if (actual < limit) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name + "  (expected under " + limit + ", got " + actual + ")");
        }
    }
}
