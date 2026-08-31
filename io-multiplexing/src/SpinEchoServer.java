import java.io.IOException;
import java.net.InetSocketAddress;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NAIVE SOLUTION #2 from the lab doc: "one employee sprinting past every line."
 *
 * One thread, non-blocking sockets, and a hot loop that asks every connection
 * "anything for me?" over and over. Also correct, and it fixes the
 * thread-count problem — but nobody ever tells it WHEN data arrives, so it can
 * never sleep. It burns a full CPU core asking questions whose answer is
 * almost always "no". Run `Experiments spin` to measure the waste.
 */
public class SpinEchoServer {

    /** Full passes over the connection list. */
    final AtomicLong loopIterations = new AtomicLong();
    /** Every individual "anything for me?" question asked (each one a real syscall). */
    final AtomicLong channelChecks = new AtomicLong();

    private ServerSocketChannel serverChannel;
    private Thread loopThread;
    private volatile boolean running;

    public void start(int port) throws IOException {
        serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(port));
        serverChannel.configureBlocking(false); // accept() now returns null instead of waiting
        running = true;
        loopThread = new Thread(this::spinLoop, "spin-loop");
        loopThread.start();
    }

    public int port() throws IOException {
        return ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
    }

    private void spinLoop() {
        ByteBuffer buf = ByteBuffer.allocate(4096);
        List<SocketChannel> clients = new ArrayList<>();
        while (running) {
            loopIterations.incrementAndGet();
            try {
                channelChecks.incrementAndGet();
                SocketChannel newClient = serverChannel.accept(); // non-blocking: null almost every time
                if (newClient != null) {
                    newClient.configureBlocking(false);
                    clients.add(newClient);
                }
                Iterator<SocketChannel> it = clients.iterator();
                while (it.hasNext()) {
                    SocketChannel ch = it.next();
                    channelChecks.incrementAndGet();
                    buf.clear();
                    int n = ch.read(buf); // non-blocking: 0 almost every time — a wasted syscall
                    if (n == -1) {
                        ch.close();
                        it.remove();
                    } else if (n > 0) {
                        buf.flip();
                        while (buf.hasRemaining()) ch.write(buf); // echo it back
                    }
                }
            } catch (IOException e) {
                if (running) e.printStackTrace();
            }
        }
    }

    /** Actual CPU time burned by the spin thread, in milliseconds. */
    public long loopThreadCpuMillis() {
        return ManagementFactory.getThreadMXBean().getThreadCpuTime(loopThread.threadId()) / 1_000_000;
    }

    public void stop() throws IOException {
        running = false;
        serverChannel.close();
    }
}
