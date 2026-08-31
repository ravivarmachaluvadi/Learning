import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ⚠ This server contains ONE subtle, realistic bug that ships to production
 * regularly in the real world. Do not read the diff against
 * SelectorEchoServer yet — go to EXERCISES.md, rung 6, and hunt it:
 * which test in EchoTest catches it, what is the symptom, and why does the
 * mechanism produce exactly that symptom?
 */
public class BrokenSelectorServer {

    final AtomicLong loopIterations = new AtomicLong();

    private Selector selector;
    private ServerSocketChannel serverChannel;
    private Thread loopThread;
    private volatile boolean running;

    public void start(int port) throws IOException {
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(port));
        serverChannel.configureBlocking(false);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        running = true;
        loopThread = new Thread(this::eventLoop, "broken-selector-loop");
        loopThread.start();
    }

    public int port() throws IOException {
        return ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
    }

    private void eventLoop() {
        while (running) {
            try {
                selector.select();
                loopIterations.incrementAndGet();
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
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
        client.register(selector, SelectionKey.OP_READ, ByteBuffer.allocate(4096));
    }

    private void handleReadable(SelectionKey key) throws IOException {
        SocketChannel ch = (SocketChannel) key.channel();
        ByteBuffer buf = (ByteBuffer) key.attachment();
        buf.clear();
        int n = ch.read(buf);
        if (n <= 0) return;
        buf.flip();
        while (buf.hasRemaining()) ch.write(buf);
    }

    public void stop() throws IOException {
        running = false;
        selector.wakeup();
        serverChannel.close();
    }
}
