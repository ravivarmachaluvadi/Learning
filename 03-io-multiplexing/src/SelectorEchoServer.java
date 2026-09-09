import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;

/**
 * THE REAL MECHANISM: io-multiplexing via java.nio.channels.Selector.
 *
 * One thread serves every connection, and — unlike SpinEchoServer — it SLEEPS
 * until the kernel tells it which connections are ready. Selector is Java's
 * portable wrapper over the OS multiplexing syscalls: epoll on Linux, kqueue
 * on macOS, IOCP-backed on Windows.
 *
 * The whole idea lives in three lines, marked THE-IDEA below:
 *   1. register each channel with the selector (build the INTEREST SET),
 *   2. selector.select() — sleep until something is ready,
 *   3. walk selectedKeys() (the READY SET) and handle only those.
 */
public class SelectorEchoServer {

    /** How many times the event loop woke up. While clients are idle this stays ~0. */
    final AtomicLong loopIterations = new AtomicLong();

    private final long slowHandlerMillis; // messages starting with "SLOW" sleep this long (0 = disabled)
    private Selector selector;
    private ServerSocketChannel serverChannel;
    private Thread loopThread;
    private volatile boolean running;

    public SelectorEchoServer() {
        this(0);
    }

    public SelectorEchoServer(long slowHandlerMillis) {
        this.slowHandlerMillis = slowHandlerMillis;
    }

    public void start(int port) throws IOException {
        selector = Selector.open(); // creates the kernel-side "switchboard" (an epoll/kqueue instance)
        serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(port));
        serverChannel.configureBlocking(false); // mandatory: a selector only accepts non-blocking channels
        // THE-IDEA (1): interest set — "kernel, wake me when a NEW CONNECTION arrives here."
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        running = true;
        loopThread = new Thread(this::eventLoop, "selector-loop");
        loopThread.start();
    }

    public int port() throws IOException {
        return ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
    }

    private void eventLoop() {
        while (running) {
            try {
                // THE-IDEA (2): the multiplexing call. This thread now SLEEPS —
                // zero CPU — until at least one registered channel is ready.
                // 10,000 idle connections cost 10,000 kernel bookkeeping entries
                // and ZERO wakeups.
                selector.select();
                loopIterations.incrementAndGet();

                // THE-IDEA (3): the ready set. Only the channels with something
                // to do are in here — we never touch the idle ones.
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    // The selector ADDS to this set but never removes; clearing
                    // handled events is OUR job, or we'd re-handle them forever.
                    it.remove();
                    if (!key.isValid()) continue;
                    if (key.isAcceptable()) handleNewConnection();
                    else if (key.isReadable()) handleReadable(key);
                }
            } catch (IOException e) {
                if (running) e.printStackTrace();
            }
        }
    }

    private void handleNewConnection() throws IOException {
        SocketChannel client = serverChannel.accept();
        client.configureBlocking(false);
        // Interest set again: "kernel, also wake me when THIS connection has data."
        // The attached buffer is this connection's private state — with one thread
        // for all connections, per-connection state must live in objects, not in
        // a thread's stack.
        client.register(selector, SelectionKey.OP_READ, ByteBuffer.allocate(4096));
    }

    private void handleReadable(SelectionKey key) throws IOException {
        SocketChannel ch = (SocketChannel) key.channel();
        ByteBuffer buf = (ByteBuffer) key.attachment();
        buf.clear();
        int n = ch.read(buf); // non-blocking, but we KNOW it's ready — this read returns data
        if (n == -1) {
            // Readiness includes goodbye: a closed peer makes the channel
            // permanently "readable" (reads return -1). We must deregister and
            // close, or select() would wake us for this corpse forever —
            // that exact bug is planted in BrokenSelectorServer.
            key.cancel();
            ch.close();
            return;
        }
        buf.flip();
        maybeSimulateSlowHandler(buf); // the deliberate sin used by `Experiments blockedloop`
        // SIMPLIFICATION: we assume the echo fits the socket's send buffer and
        // write in a tight loop. A real server must handle a full send buffer by
        // registering OP_WRITE — see failure scenario "the write side" in the doc.
        while (buf.hasRemaining()) ch.write(buf);
    }

    private void maybeSimulateSlowHandler(ByteBuffer buf) {
        if (slowHandlerMillis > 0 && buf.remaining() >= 4
                && buf.get(0) == 'S' && buf.get(1) == 'L' && buf.get(2) == 'O' && buf.get(3) == 'W') {
            try {
                // THE CARDINAL SIN: sleeping (or any slow/blocking work) on the
                // event loop thread. Every other connection is now frozen,
                // because the one thread they all share is asleep here.
                Thread.sleep(slowHandlerMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Actual CPU time burned by the event-loop thread, in milliseconds. */
    public long loopThreadCpuMillis() {
        return ManagementFactory.getThreadMXBean().getThreadCpuTime(loopThread.threadId()) / 1_000_000;
    }

    public void stop() throws IOException {
        running = false;
        selector.wakeup(); // select() is asleep; poke it so the loop can exit
        serverChannel.close();
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 7002;
        SelectorEchoServer server = new SelectorEchoServer();
        server.start(port);
        System.out.println("SelectorEchoServer on port " + server.port()
                + " — try:  nc localhost " + server.port());
    }
}
