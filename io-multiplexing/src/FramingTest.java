import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * Tests for EXERCISE 5. Run:  java src/FramingTest.java
 *
 * All three fail against the stub. All three pass once
 * FramingServer.nextCompleteMessage() is correctly implemented.
 *
 * Each test attacks framing from a different direction — together they cover
 * every way a byte stream can disagree with your message boundaries.
 */
public class FramingTest {

    static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        one_message_per_write();
        two_messages_in_a_single_write();
        one_large_message_split_across_many_packets();

        System.out.println();
        System.out.printf("%d passed, %d failed%n", passed, failed);
        if (failed > 0) {
            System.out.println("""

                    Still red? That is the exercise. Read the contract at the top of
                    src/FramingServer.java, then implement nextCompleteMessage().
                    Worked answer in SOLUTIONS.md — try first.""");
            System.exit(1);
        }
    }

    /** The easy case: each message arrives in its own write. */
    static void one_message_per_write() throws Exception {
        FramingServer server = new FramingServer();
        server.start(0);
        try (SocketChannel c = connect(server.port())) {
            writeFrame(c, "hello");
            assertEquals("1:hello", readFrame(c), "one_message_per_write (first)");
            writeFrame(c, "again");
            assertEquals("2:again", readFrame(c), "one_message_per_write (counter advances)");
        } finally {
            server.stop();
        }
    }

    /**
     * Two complete messages arrive glued together in ONE read. A loop that
     * handles only the first message per wakeup leaves the second stranded in
     * the buffer — a classic framing bug that looks like "random hangs".
     */
    static void two_messages_in_a_single_write() throws Exception {
        FramingServer server = new FramingServer();
        server.start(0);
        try (SocketChannel c = connect(server.port())) {
            byte[] a = FramingServer.encode("", "AAA".getBytes(StandardCharsets.UTF_8));
            byte[] b = FramingServer.encode("", "BB".getBytes(StandardCharsets.UTF_8));
            ByteBuffer both = ByteBuffer.allocate(a.length + b.length).put(a).put(b).flip();
            writeFully(c, both);
            assertEquals("1:AAA", readFrame(c), "two_messages_in_a_single_write (first)");
            assertEquals("2:BB", readFrame(c), "two_messages_in_a_single_write (second)");
        } finally {
            server.stop();
        }
    }

    /**
     * README §13.3, made into a test: one 200 KB message, deliberately dribbled
     * out in small chunks so the server is repeatedly handed half a message and
     * must hold the fragment between readiness events.
     */
    static void one_large_message_split_across_many_packets() throws Exception {
        FramingServer server = new FramingServer();
        server.start(0);
        try (SocketChannel c = connect(server.port())) {
            int size = 200 * 1024;
            StringBuilder sb = new StringBuilder(size);
            for (int i = 0; i < size; i++) sb.append((char) ('a' + (i % 26)));
            String payload = sb.toString();

            byte[] frame = FramingServer.encode("", payload.getBytes(StandardCharsets.UTF_8));
            ByteBuffer out = ByteBuffer.wrap(frame);
            // Dribble it: 3000 bytes at a time, with pauses. Note the first write
            // sends only 2 bytes — so even the 4-byte LENGTH PREFIX arrives split
            // in half, and the server must cope with knowing neither the payload
            // nor yet how long it will be.
            writeFully(c, out.slice(0, 2));
            out.position(2);
            sleep(20);
            while (out.hasRemaining()) {
                int chunk = Math.min(3000, out.remaining());
                writeFully(c, out.slice(out.position(), chunk));
                out.position(out.position() + chunk);
                sleep(1);
            }
            String reply = readFrame(c);
            assertEquals("1:" + payload.length(), reassemble(reply),
                    "one_large_message_split_across_many_packets");
        } finally {
            server.stop();
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Summarise a big reply as "<counter>:<payload length>" so failures stay readable. */
    static String reassemble(String reply) {
        int colon = reply.indexOf(':');
        if (colon < 0) return reply;
        return reply.substring(0, colon + 1) + (reply.length() - colon - 1);
    }

    /**
     * Connects, then switches to non-blocking. Non-blocking matters here: against
     * the unimplemented stub the server never replies, and a blocking read would
     * hang this test forever instead of failing it.
     */
    static SocketChannel connect(int port) throws IOException {
        SocketChannel c = SocketChannel.open(new InetSocketAddress("localhost", port));
        c.configureBlocking(false);
        return c;
    }

    static void writeFrame(SocketChannel c, String payload) throws IOException {
        ByteBuffer out = ByteBuffer.wrap(
                FramingServer.encode("", payload.getBytes(StandardCharsets.UTF_8)));
        writeFully(c, out);
    }

    /** A non-blocking write can accept only part of the buffer — README §13.4. */
    static void writeFully(SocketChannel c, ByteBuffer out) throws IOException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (out.hasRemaining() && System.nanoTime() < deadline) {
            if (c.write(out) == 0) sleep(1);
        }
    }

    /** Client-side frame reader with a hard deadline, so a stub fails instead of hanging. */
    static String readFrame(SocketChannel c) throws IOException {
        ByteBuffer lenBuf = ByteBuffer.allocate(4);
        if (!readFully(c, lenBuf)) return "<no reply>";
        int n = lenBuf.flip().getInt();
        if (n < 0 || n > 8 * 1024 * 1024) return "<bad length " + n + ">";
        ByteBuffer body = ByteBuffer.allocate(n);
        if (!readFully(c, body)) return "<truncated reply>";
        return new String(body.array(), StandardCharsets.UTF_8);
    }

    static boolean readFully(SocketChannel c, ByteBuffer buf) throws IOException {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (buf.hasRemaining()) {
            if (System.nanoTime() > deadline) return false;
            int n = c.read(buf);
            if (n < 0) return false;
            if (n == 0) sleep(1); // nothing ready yet; this is normal, not an error
        }
        return true;
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void assertEquals(String expected, String actual, String name) {
        if (expected.equals(actual)) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name + "  (expected \"" + expected + "\", got \"" + actual + "\")");
        }
    }
}
