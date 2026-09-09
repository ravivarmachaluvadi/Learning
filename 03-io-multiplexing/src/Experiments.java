import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The runnable failure experiments from README.md stage 14.
 *
 *   java src/Experiments.java threads      [n]   cost of one-thread-per-connection
 *   java src/Experiments.java spin         [n]   cost of asking instead of being told
 *   java src/Experiments.java blockedloop        one slow handler freezes everyone
 *   java src/Experiments.java readiness          "ready" != "your message arrived"
 *   java src/Experiments.java all
 *
 * Each experiment prints numbers measured on YOUR machine, right now.
 */
public class Experiments {

    public static void main(String[] args) throws Exception {
        String which = args.length > 0 ? args[0] : "all";
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 200;
        switch (which) {
            case "threads"     -> threads(n);
            case "spin"        -> spin(n);
            case "blockedloop" -> blockedLoop();
            case "readiness"   -> readiness();
            case "all"         -> { threads(n); spin(n); blockedLoop(); readiness(); }
            default -> System.out.println("unknown experiment: " + which);
        }
    }

    // ---------------------------------------------------------------- exp 1

    /**
     * EXPERIMENT 1 — "one employee per phone line".
     * Open n connections that say NOTHING, then count the threads each design
     * needs to hold them. Predict the two numbers before you run it.
     */
    static void threads(int n) throws Exception {
        header("EXPERIMENT 1: " + n + " idle connections — how many threads does it cost?");

        BlockingEchoServer blocking = new BlockingEchoServer();
        blocking.start(0);
        List<SocketChannel> a = openIdleConnections(blocking.port(), n);
        Thread.sleep(300);
        System.out.printf("  thread-per-connection : %d connections -> %d handler threads (%d JVM threads total)%n",
                a.size(), BlockingEchoServer.handlerThreadCount(), Thread.activeCount());
        closeAll(a);
        blocking.stop();

        SelectorEchoServer selector = new SelectorEchoServer();
        selector.start(0);
        List<SocketChannel> b = openIdleConnections(selector.port(), n);
        Thread.sleep(300);
        System.out.printf("  io-multiplexing       : %d connections -> 1 event-loop thread%n", b.size());
        closeAll(b);
        selector.stop();

        System.out.println("""
              WHY: a blocked thread is not free. Each one reserves ~1 MB of stack
              address space and a scheduler entry, and every context switch between
              them costs the CPU real work. The selector holds the same connections
              as kernel bookkeeping entries instead of threads.""");
    }

    // ---------------------------------------------------------------- exp 2

    /**
     * EXPERIMENT 2 — asking vs being told.
     * Same n idle connections, held for 3 seconds by two single-threaded
     * servers. One polls in a hot loop; one sleeps in select(). Watch the CPU.
     */
    static void spin(int n) throws Exception {
        header("EXPERIMENT 2: " + n + " idle connections, 3 seconds — polling vs being woken");
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        if (!mx.isThreadCpuTimeSupported()) {
            System.out.println("  (per-thread CPU timing unavailable on this JVM; skipping)");
            return;
        }

        // Everything below is measured as a DELTA across exactly the same
        // 3-second idle window, so connection-setup cost is excluded from both.
        SpinEchoServer spinner = new SpinEchoServer();
        spinner.start(0);
        List<SocketChannel> a = openIdleConnections(spinner.port(), n);
        long spinCpu = spinner.loopThreadCpuMillis(), spinAsks = spinner.channelChecks.get();
        long spinPasses = spinner.loopIterations.get();
        Thread.sleep(3000);
        System.out.printf("  hot-loop polling : %,d ms CPU burned | %,d loop passes | %,d readiness questions asked%n",
                spinner.loopThreadCpuMillis() - spinCpu,
                spinner.loopIterations.get() - spinPasses,
                spinner.channelChecks.get() - spinAsks);
        closeAll(a);
        spinner.stop();

        SelectorEchoServer selector = new SelectorEchoServer();
        selector.start(0);
        List<SocketChannel> b = openIdleConnections(selector.port(), n);
        long selCpu = selector.loopThreadCpuMillis(), selWakeups = selector.loopIterations.get();
        Thread.sleep(3000);
        System.out.printf("  io-multiplexing  : %,d ms CPU burned | %,d wakeups | %,d readiness questions asked%n",
                selector.loopThreadCpuMillis() - selCpu,
                selector.loopIterations.get() - selWakeups,
                0);
        closeAll(b);
        selector.stop();

        System.out.println("""
              WHY: the poller asks every connection "anything yet?" millions of
              times and hears "no" almost every time — each question is a real
              syscall. The selector asks ONCE, hands the kernel the whole list,
              and sleeps. Nothing arrived, so it was never woken.""");
    }

    // ---------------------------------------------------------------- exp 3

    /**
     * EXPERIMENT 3 — the cardinal sin.
     * Client A sends work that takes the handler 1 second. Client B sends
     * "ping" 100 ms later. How long does B wait for its echo?
     */
    static void blockedLoop() throws Exception {
        header("EXPERIMENT 3: one slow handler — who else pays for it?");

        SelectorEchoServer selector = new SelectorEchoServer(1000);
        selector.start(0);
        long selectorLatency = slowThenPing(selector.port());
        selector.stop();
        System.out.printf("  io-multiplexing (1 shared loop) : B's ping took %,d ms%n", selectorLatency);

        BlockingEchoServer blocking = new BlockingEchoServer(1000);
        blocking.start(0);
        long blockingLatency = slowThenPing(blocking.port());
        blocking.stop();
        System.out.printf("  thread-per-connection          : B's ping took %,d ms%n", blockingLatency);

        System.out.println("""
              WHY: shared loop means shared fate. The event loop is ONE thread, so
              while it sleeps inside A's handler nobody calls select() and B's
              already-arrived bytes sit unread. Thread-per-connection pays more for
              idle connections but isolates slow ones. This is the real trade, and
              it is why event-loop code must never block: no JDBC, no
              Thread.sleep, no synchronous HTTP calls on the loop thread.""");
    }

    /** A sends a 1-second request; 100 ms later B sends a ping. Returns B's round-trip in ms. */
    private static long slowThenPing(int port) throws Exception {
        SocketChannel a = SocketChannel.open(new InetSocketAddress("localhost", port));
        SocketChannel b = SocketChannel.open(new InetSocketAddress("localhost", port));
        a.write(ByteBuffer.wrap("SLOW-request".getBytes(StandardCharsets.UTF_8)));
        Thread.sleep(100);
        long start = System.nanoTime();
        b.write(ByteBuffer.wrap("ping".getBytes(StandardCharsets.UTF_8)));
        ByteBuffer reply = ByteBuffer.allocate(64);
        b.read(reply); // blocking client read: returns when the echo comes back
        long millis = (System.nanoTime() - start) / 1_000_000;
        a.close();
        b.close();
        return millis;
    }

    // ---------------------------------------------------------------- exp 4

    /**
     * EXPERIMENT 4 — readiness is not completion.
     * Send one 512 KB "message" and count how many separate readiness events and
     * reads it takes the client to get it all back. Predict: 1?
     */
    static void readiness() throws Exception {
        header("EXPERIMENT 4: one 512 KB message — how many readiness events to receive it?");

        SelectorEchoServer server = new SelectorEchoServer();
        server.start(0);

        int size = 512 * 1024;
        byte[] payload = new byte[size];
        for (int i = 0; i < size; i++) payload[i] = (byte) ('a' + (i % 26));

        SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", server.port()));
        client.configureBlocking(false);
        Selector clientSelector = Selector.open();
        client.register(clientSelector, SelectionKey.OP_READ);

        ByteBuffer out = ByteBuffer.wrap(payload);
        ByteBuffer in = ByteBuffer.allocate(64 * 1024);
        int received = 0, wakeups = 0, reads = 0, smallest = Integer.MAX_VALUE, largest = 0;

        while (received < size) {
            if (out.hasRemaining()) client.write(out); // may also write only part of it
            if (clientSelector.select(2000) == 0) break;
            wakeups++;
            clientSelector.selectedKeys().clear();
            int n;
            while ((n = client.read(in.clear())) > 0) {
                reads++;
                received += n;
                smallest = Math.min(smallest, n);
                largest = Math.max(largest, n);
            }
        }

        System.out.printf("  bytes sent: %,d | bytes received: %,d%n", size, received);
        System.out.printf("  readiness events: %,d | read() calls: %,d | smallest chunk: %,d bytes | largest: %,d bytes%n",
                wakeups, reads, smallest == Integer.MAX_VALUE ? 0 : smallest, largest);

        client.close();
        clientSelector.close();
        server.stop();

        System.out.println("""
              WHY: "readable" means "a read right now will not block" — it says
              nothing about how much, and nothing about where your message ends.
              TCP is a byte stream, not a message queue. Every real protocol on top
              of a selector must therefore do FRAMING (a length prefix, or a
              delimiter) and keep a per-connection buffer holding the half-arrived
              message between wakeups. That buffer is why non-blocking code keeps
              state in objects instead of on a thread's stack.""");
    }

    // ---------------------------------------------------------------- helpers

    static List<SocketChannel> openIdleConnections(int port, int n) {
        List<SocketChannel> channels = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            try {
                channels.add(SocketChannel.open(new InetSocketAddress("localhost", port)));
            } catch (IOException e) {
                // Two different ceilings, commonly confused:
                //   "Too many open files"        -> file-descriptor limit, raise `ulimit -n`
                //   "Can't assign requested address" -> EPHEMERAL PORT exhaustion on the
                //   CLIENT side. Every outbound connection burns a source port, and closed
                //   ones sit in TIME_WAIT for minutes. This is a limit of the load generator,
                //   NOT of the server — the classic load-testing mismeasurement.
                String hint = String.valueOf(e.getMessage()).contains("assign")
                        ? "ephemeral ports exhausted on the CLIENT side (TIME_WAIT); "
                          + "this is a load-generator limit, not a server limit"
                        : "raise `ulimit -n` for more";
                System.out.printf("  (stopped at %,d of %,d connections: %s — %s)%n",
                        channels.size(), n, e.getMessage(), hint);
                break;
            }
        }
        return channels;
    }

    static void closeAll(List<SocketChannel> channels) {
        for (SocketChannel c : channels) {
            try { c.close(); } catch (IOException ignored) { }
        }
    }

    static void header(String title) {
        System.out.println();
        System.out.println("=".repeat(78));
        System.out.println(title);
        System.out.println("=".repeat(78));
    }
}
