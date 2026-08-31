import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;

/**
 * EXERCISE 5 (rung: implement). See EXERCISES.md.
 *
 * README §13.3 showed that one 512 KB message arrived as 76 separate readiness
 * events. A byte echo does not care. A real protocol does — it must know where
 * each message ENDS. That is FRAMING, and it is the skill this exercise drills.
 *
 * THE WIRE FORMAT
 *   [4-byte big-endian length N][N bytes of payload]
 *
 * THE CONTRACT
 *   For each COMPLETE message received on a connection, reply with one frame
 *   containing "<n>:<payload>", where n is how many complete messages that
 *   connection has sent so far, starting at 1.
 *
 *   A byte-for-byte echo cannot satisfy this — you have to actually detect
 *   message boundaries, which is the point.
 *
 * YOUR JOB
 *   Implement nextCompleteMessage() below. Everything else is written for you:
 *   the event loop, the per-connection accumulator, and the reply encoding.
 *
 * CHECK YOURSELF
 *   java src/FramingTest.java        (3 tests; all fail until you implement it)
 */
public class FramingServer {

    /**
     * Per-connection state. With one thread serving every connection there is no
     * per-connection stack, so a half-arrived message must live here (README §13.3).
     */
    static class Conn {
        byte[] buf = new byte[1024]; // bytes received but not yet consumed
        int len = 0;                 // how many of them are valid
        int messageCount = 0;        // complete messages seen on this connection

        void append(byte[] data, int n) {
            while (len + n > buf.length) {
                byte[] bigger = new byte[buf.length * 2];
                System.arraycopy(buf, 0, bigger, 0, len);
                buf = bigger;
            }
            System.arraycopy(data, 0, buf, len, n);
            len += n;
        }

        /** Drop the first {@code bytes} bytes, shifting the remainder to the front. */
        void consume(int bytes) {
            System.arraycopy(buf, bytes, buf, 0, len - bytes);
            len -= bytes;
        }
    }

    // ====================================================================
    // ======================  YOUR IMPLEMENTATION  =======================
    // ====================================================================

    /**
     * Return the payload of the next complete message in {@code c}'s buffer and
     * remove that whole frame (prefix included) from the buffer — or return
     * {@code null} if a complete message has not arrived yet.
     *
     * Reminders:
     *   - c.buf holds c.len valid bytes, starting at index 0.
     *   - The length prefix is 4 bytes, BIG-ENDIAN. You need all 4 before you
     *     can even know how long the message is. Fewer than 4 means: not yet.
     *   - Having the prefix is not enough; you need 4 + N bytes in total.
     *   - Use c.consume(k) to drop bytes you have taken.
     *   - Do NOT block, do NOT read from the socket here. You only get the
     *     bytes that have arrived so far. Being called with half a message and
     *     returning null is the NORMAL case, not an error.
     *
     * Hint: ByteBuffer.wrap(c.buf).getInt(0) reads a big-endian int at index 0.
     */
    static byte[] nextCompleteMessage(Conn c) {
        // TODO: implement me. Delete the line below and write the real thing.
        return null;
    }

    // ====================================================================
    // ==================  SCAFFOLDING — already written  ==================
    // ====================================================================

    private Selector selector;
    private ServerSocketChannel serverChannel;
    private volatile boolean running;

    public void start(int port) throws IOException {
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(port));
        serverChannel.configureBlocking(false);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        running = true;
        new Thread(this::eventLoop, "framing-loop").start();
    }

    public int port() throws IOException {
        return ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
    }

    private void eventLoop() {
        byte[] scratch = new byte[16 * 1024];
        ByteBuffer readBuf = ByteBuffer.wrap(scratch);
        while (running) {
            try {
                selector.select();
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (!key.isValid()) continue;
                    if (key.isAcceptable()) {
                        SocketChannel client = serverChannel.accept();
                        client.configureBlocking(false);
                        client.register(selector, SelectionKey.OP_READ, new Conn());
                    } else if (key.isReadable()) {
                        SocketChannel ch = (SocketChannel) key.channel();
                        Conn conn = (Conn) key.attachment();
                        readBuf.clear();
                        int n = ch.read(readBuf);
                        if (n == -1) { key.cancel(); ch.close(); continue; }
                        if (n == 0) continue;
                        conn.append(scratch, n);
                        // Drain every message that is now complete — there may be
                        // zero (half a message arrived) or several (two messages
                        // arrived in one packet). Both are normal.
                        byte[] payload;
                        while ((payload = nextCompleteMessage(conn)) != null) {
                            conn.messageCount++;
                            byte[] reply = encode(conn.messageCount + ":", payload);
                            ByteBuffer out = ByteBuffer.wrap(reply);
                            while (out.hasRemaining()) ch.write(out);
                        }
                    }
                }
            } catch (IOException e) {
                if (running) e.printStackTrace();
            }
        }
    }

    /** Build one frame: 4-byte big-endian length, then prefix + payload. */
    static byte[] encode(String prefix, byte[] payload) {
        byte[] p = prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int total = p.length + payload.length;
        ByteBuffer out = ByteBuffer.allocate(4 + total);
        out.putInt(total);
        out.put(p);
        out.put(payload);
        return out.array();
    }

    public void stop() throws IOException {
        running = false;
        selector.wakeup();
        serverChannel.close();
    }
}
