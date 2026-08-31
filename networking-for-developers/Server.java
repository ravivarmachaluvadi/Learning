import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.*;

/**
 * A deliberately misbehaving TCP server.
 *
 * Every failure a developer blames on "the network" can be reproduced on
 * localhost by a server that behaves badly in one specific way. This class is
 * the set of bad behaviours, so the experiments never need a real outage.
 *
 * Runs in the same JVM as the client, on an ephemeral port, one virtual thread
 * per connection.
 */
final class Server implements AutoCloseable {

    enum Mode {
        /** Replies immediately. The control case. */
        ECHO,
        /** Accepts the connection and then says nothing, forever.
         *  This is what a hung upstream, a deadlocked thread pool, or a
         *  dropped-response path looks like from the client side. */
        SILENT,
        /** Accepts, waits `delayMs`, then replies. A slow-but-working server. */
        SLOW,
        /** Accepts, replies, then closes the connection after `idleMs` of
         *  silence — exactly what a NAT box or load balancer does to an idle
         *  pooled connection. The client is never told. */
        CLOSE_IDLE
    }

    private final ServerSocket socket;
    private final Mode mode;
    private final int delayMs;
    private final AtomicInteger accepted = new AtomicInteger();
    private volatile boolean running = true;

    Server(Mode mode, int delayMs) throws IOException {
        this.mode = mode;
        this.delayMs = delayMs;
        this.socket = new ServerSocket(0, 64, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().name("server-accept").start(this::acceptLoop);
    }

    int port() { return socket.getLocalPort(); }

    /** How many TCP connections were established. The number that reveals
     *  whether a client is reusing connections or making new ones. */
    int connectionsAccepted() { return accepted.get(); }

    private void acceptLoop() {
        while (running) {
            try {
                Socket c = socket.accept();
                accepted.incrementAndGet();
                Thread.ofVirtual().start(() -> handle(c));
            } catch (IOException e) {
                return; // socket closed — normal shutdown
            }
        }
    }

    private void handle(Socket c) {
        try (c;
             BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream()));
             PrintWriter out = new PrintWriter(c.getOutputStream(), true)) {

            if (mode == Mode.SILENT) {
                // Read the request, then never answer. The connection stays
                // OPEN — which is why the client cannot tell this apart from a
                // server that is merely thinking.
                in.readLine();
                Thread.sleep(Long.MAX_VALUE);
                return;
            }

            String line;
            while ((line = readWithIdleClose(in, c)) != null) {
                if (mode == Mode.SLOW) Thread.sleep(delayMs);
                out.println("ok:" + line);
            }
        } catch (Exception ignored) {
            // Client went away, or we closed on it. Both are normal here.
        }
    }

    /** In CLOSE_IDLE mode, hang up silently after `delayMs` of client silence. */
    private String readWithIdleClose(BufferedReader in, Socket c) throws IOException {
        if (mode != Mode.CLOSE_IDLE) return in.readLine();
        c.setSoTimeout(delayMs);
        try {
            return in.readLine();
        } catch (SocketTimeoutException e) {
            c.close();   // no FIN handshake courtesy, no warning — just gone
            return null;
        }
    }

    @Override public void close() throws IOException {
        running = false;
        socket.close();
    }

    /** A port with nothing listening on it — used to produce a connection
     *  REFUSED, which is a completely different signal from a timeout. */
    static int closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();   // closed on return; nobody is listening now
        }
    }
}
