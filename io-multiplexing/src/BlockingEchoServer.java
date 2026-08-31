import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NAIVE SOLUTION #1 from the lab doc: "one employee per phone line."
 *
 * Every connection gets its own dedicated platform thread, and that thread
 * spends almost all of its life blocked inside read(), waiting for bytes.
 *
 * This design is simple and CORRECT — its problem is cost, not correctness:
 * every idle connection pins a whole OS thread (about a megabyte of reserved
 * stack plus a scheduler entry), so 10,000 mostly-silent connections cost
 * 10,000 threads. Run `Experiments threads` to see it.
 */
public class BlockingEchoServer {

    private final long slowHandlerMillis; // messages starting with "SLOW" sleep this long (0 = disabled)
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;
    private final AtomicInteger handlerSeq = new AtomicInteger();

    public BlockingEchoServer() {
        this(0);
    }

    public BlockingEchoServer(long slowHandlerMillis) {
        this.slowHandlerMillis = slowHandlerMillis;
    }

    public void start(int port) throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        acceptThread = new Thread(this::acceptLoop, "blocking-accept");
        acceptThread.start();
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept(); // blocks until a new connection arrives
                // One thread per connection — the line that does not scale.
                new Thread(() -> handle(client),
                        "blocking-handler-" + handlerSeq.incrementAndGet()).start();
            } catch (IOException e) {
                if (running) e.printStackTrace();
            }
        }
    }

    private void handle(Socket client) {
        try (client) {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            byte[] buf = new byte[4096];
            while (true) {
                // Blocks THIS thread only. A slow or silent client costs its own
                // thread and nobody else's — isolation is the one thing this
                // model does better than a shared event loop.
                int n = in.read(buf);
                if (n == -1) return; // peer closed the connection
                maybeSimulateSlowHandler(buf, n);
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
            // client went away mid-conversation; the thread just ends
        }
    }

    private void maybeSimulateSlowHandler(byte[] buf, int n) {
        if (slowHandlerMillis > 0 && n >= 4
                && buf[0] == 'S' && buf[1] == 'L' && buf[2] == 'O' && buf[3] == 'W') {
            try {
                Thread.sleep(slowHandlerMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** How many threads are currently dedicated to serving connections. */
    public static int handlerThreadCount() {
        int count = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("blocking-handler-")) count++;
        }
        return count;
    }

    public void stop() throws IOException {
        running = false;
        serverSocket.close();
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 7001;
        BlockingEchoServer server = new BlockingEchoServer();
        server.start(port);
        System.out.println("BlockingEchoServer on port " + server.port()
                + " — try:  nc localhost " + server.port());
    }
}
