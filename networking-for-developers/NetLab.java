import java.io.*;
import java.net.*;
import java.util.*;

/**
 * NetLab — six experiments on what actually happens between
 * `httpClient.send(request)` and a response arriving.
 *
 * Run:  java NetLab.java            (all six)
 *       java NetLab.java 2          (just experiment 2)
 *
 * Experiments 1 and 2b reach the real internet. Everything else runs against a
 * deliberately misbehaving server inside this same JVM, so the failures are
 * reproducible on a plane.
 */
public class NetLab {

    /** Measured on this machine with `ping 8.8.8.8`: avg 16 ms round trip.
     *  Used to make local experiments honest about what distance costs. */
    static final int RTT_MS = 16;

    public static void main(String[] args) throws Exception {
        Set<String> pick = new HashSet<>(Arrays.asList(args));
        banner("NETWORKING FOR DEVELOPERS — six things your one line of code hides");
        if (run(pick, "1")) exp1_whereDidTheTimeGo();
        if (run(pick, "2")) exp2_refusedVersusDropped();
        if (run(pick, "3")) exp3_silenceIsNotAnError();
        if (run(pick, "4")) exp4_costOfANewConnection();
        if (run(pick, "5")) exp5_theIdleConnectionThatDied();
        if (run(pick, "6")) exp6_aStreamNotMessages();
        System.out.println("\nDone. README.md stage 14 says what each experiment proves.");
    }

    static boolean run(Set<String> pick, String n) { return pick.isEmpty() || pick.contains(n); }

    // ------------------------------------------------------------------
    // 1 — Where does the time in a request actually go?
    // ------------------------------------------------------------------
    static void exp1_whereDidTheTimeGo() {
        banner("EXPERIMENT 1 — where did the time go?");
        System.out.println("""
                One HTTPS request is at least three separate operations, and any of
                them can dominate. We time each phase on its own.

                Watch the DNS column. Everything else is physics; that column is
                configuration, and it is where the surprises live.""");

        String[] hosts = { "www.google.com", "cloudflare.com", "api.github.com", "github.com" };

        System.out.printf("%n  %-20s %11s %11s %11s   %s%n",
                "host", "DNS", "TCP", "TLS", "address");
        System.out.println("  " + "-".repeat(75));

        for (String h : hosts) {
            long dns = Probe.timeResolve(h);
            if (dns < 0) { System.out.printf("  %-20s   name did not resolve (offline?)%n", h); continue; }
            String addr;
            long tcp, tls;
            try {
                addr = InetAddress.getByName(h).getHostAddress();
                tcp = Probe.timeConnect(h, 443, 5000);
                tls = Probe.timeTls(h, 443, 5000);
            } catch (IOException e) {
                System.out.printf("  %-20s %,10d   unreachable (%s)%n", h, dns, e.getClass().getSimpleName());
                continue;
            }
            System.out.printf("  %-20s %,8d ms %,8d ms %,8d ms   %s%n", h, dns, tcp, tls, addr);
        }

        System.out.println("""

                  How to read this. TCP is one round trip and TLS is roughly one
                  more, so those two columns are mostly a measure of DISTANCE — they
                  are the price of the speed of light and you cannot argue with them.

                  DNS is not distance. A cached answer costs under a millisecond. If
                  a DNS number is large, and especially if it is a suspiciously round
                  number like 5,000 ms, that is not distance — that is a timeout
                  somewhere in your resolver chain, and it is costing you that on
                  every cold lookup.

                  Stage 11's first failure walks through a real one found on this
                  machine while writing the lab.""");
    }

    // ------------------------------------------------------------------
    // 2 — The single most useful distinction in network debugging
    // ------------------------------------------------------------------
    static void exp2_refusedVersusDropped() throws Exception {
        banner("EXPERIMENT 2 — refused, or ignored?");
        System.out.println("""
                Two hosts you cannot reach. In your logs both become "connection
                failed". They are opposite problems with opposite fixes, and the
                only thing that separates them is HOW LONG the failure took.""");

        int timeout = 3000;
        int deadPort = Server.closedPort();

        System.out.println("\n  A · a machine that IS there, with nothing on that port:");
        Probe.attempt("127.0.0.1:" + deadPort, timeout,
                () -> Probe.timeConnect("127.0.0.1", deadPort, timeout));

        System.out.println("\n  B · an address that routes nowhere (packets vanish):");
        Probe.attempt("10.255.255.1:443", timeout,
                () -> Probe.timeConnect("10.255.255.1", 443, timeout));

        System.out.println("""

                  The lesson, and it is worth memorising:

                    fast failure  ->  something ANSWERED you. The host is up and
                                      reachable; the port is wrong or the process is
                                      down. Your packets are getting there.

                    slow failure  ->  NOTHING answered. A firewall or security group
                                      is dropping the packets, or the address routes
                                      nowhere. Your packets are not getting there.

                  A refusal is a reply. Silence is not. Every retry against silence
                  costs you a full timeout and learns nothing new.""");
    }

    // ------------------------------------------------------------------
    // 3 — A connected socket tells you nothing about whether it works
    // ------------------------------------------------------------------
    static void exp3_silenceIsNotAnError() throws Exception {
        banner("EXPERIMENT 3 — the connection succeeded and the request still hung");
        System.out.println("""
                A server that accepts your connection and then never answers. The
                TCP connection is healthy and open the entire time. We try it twice:
                once bounding only the connect, once bounding the read as well.""");

        try (Server s = new Server(Server.Mode.SILENT, 0)) {
            System.out.println("\n  A · connect timeout set, NO read timeout — bounded here by a 4s watchdog:");
            long t0 = System.nanoTime();
            Thread worker = Thread.ofVirtual().start(() -> {
                try (Socket c = new Socket()) {
                    c.connect(new InetSocketAddress("127.0.0.1", s.port()), 2000);
                    new PrintWriter(c.getOutputStream(), true).println("hello");
                    new BufferedReader(new InputStreamReader(c.getInputStream())).readLine();
                } catch (Exception ignored) { }
            });
            worker.join(java.time.Duration.ofMillis(4000));
            boolean stillStuck = worker.isAlive();
            System.out.printf("      after %,d ms the request is %s%n",
                    Probe.ms(t0), stillStuck ? "STILL WAITING — it would wait forever" : "finished");
            worker.interrupt();

            System.out.println("\n  B · same server, read timeout of 800 ms:");
            Probe.attempt("read timeout 800ms", 800, () -> {
                try (Socket c = new Socket()) {
                    c.connect(new InetSocketAddress("127.0.0.1", s.port()), 2000);
                    c.setSoTimeout(800);                 // bounds each read, not the whole request
                    new PrintWriter(c.getOutputStream(), true).println("hello");
                    new BufferedReader(new InputStreamReader(c.getInputStream())).readLine();
                }
            });
        }

        System.out.println("""

                  A connect timeout only bounds the handshake. It expires the moment
                  the connection is established, and after that your thread can block
                  forever on a healthy socket that simply has nothing to say.

                  This is the most common way a service takes an entire thread pool
                  down: not an error, but the absence of one.""");
    }

    // ------------------------------------------------------------------
    // 4 — What a new connection costs, in round trips
    // ------------------------------------------------------------------
    static void exp4_costOfANewConnection() throws Exception {
        banner("EXPERIMENT 4 — the price of a new connection");
        System.out.printf("""
                20 small requests, two ways: a new connection each time, or one
                connection reused. Loopback has no distance, so we add the round trip
                measured from this machine to 8.8.8.8 (%d ms) to make it honest.

                A new HTTPS connection costs about 2 round trips before your first
                request byte moves: 1 for the TCP handshake, ~1 for TLS.%n""", RTT_MS);

        try (Server s = new Server(Server.Mode.ECHO, 0)) {
            int n = 20;

            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                simulateSetup();                     // the handshakes you pay for
                try (Socket c = new Socket("127.0.0.1", s.port())) {
                    var out = new PrintWriter(c.getOutputStream(), true);
                    var in = new BufferedReader(new InputStreamReader(c.getInputStream()));
                    out.println("req" + i);
                    in.readLine();
                }
            }
            long fresh = Probe.ms(t0);
            int freshConns = s.connectionsAccepted();

            long t1 = System.nanoTime();
            simulateSetup();                         // paid exactly once
            try (Socket c = new Socket("127.0.0.1", s.port())) {
                var out = new PrintWriter(c.getOutputStream(), true);
                var in = new BufferedReader(new InputStreamReader(c.getInputStream()));
                for (int i = 0; i < n; i++) { out.println("req" + i); in.readLine(); }
            }
            long reused = Probe.ms(t1);
            int reusedConns = s.connectionsAccepted() - freshConns;

            System.out.printf("%n  %-26s %8s %14s %14s%n", "", "time", "connections", "per request");
            System.out.println("  " + "-".repeat(66));
            System.out.printf("  %-26s %,6d ms %11d %,13d ms%n", "new connection each time", fresh, freshConns, fresh / n);
            System.out.printf("  %-26s %,6d ms %11d %,13d ms%n", "one connection reused", reused, reusedConns, reused / n);
            System.out.printf("%n  reuse was %.1fx faster, and the work done was identical.%n",
                    (double) fresh / Math.max(1, reused));
        }

        System.out.println("""

                  This is why every HTTP client you have ever used keeps a connection
                  pool, and why `new HttpClient()` inside a request handler is a
                  performance bug rather than a style preference: it throws the pool
                  away on every call and pays the handshakes again.""");
    }

    /** Stand in for the handshakes a real connection over a network would need. */
    static void simulateSetup() throws InterruptedException {
        Thread.sleep(2L * RTT_MS);   // ~1 RTT for TCP + ~1 RTT for TLS
    }

    // ------------------------------------------------------------------
    // 5 — The pooled connection that died while you weren't looking
    // ------------------------------------------------------------------
    static void exp5_theIdleConnectionThatDied() throws Exception {
        banner("EXPERIMENT 5 — the idle connection that died");
        System.out.println("""
                Connection pools keep sockets open between requests. Meanwhile the
                boxes in the middle — NAT gateways, load balancers, firewalls — drop
                connection state that has been idle too long, and do not tell either
                end.

                Here the server hangs up after 600 ms of silence. The client holds
                the socket and does not notice.""");

        try (Server s = new Server(Server.Mode.CLOSE_IDLE, 600)) {
            try (Socket c = new Socket("127.0.0.1", s.port())) {
                c.setSoTimeout(2000);
                var out = new PrintWriter(c.getOutputStream(), true);
                var in = new BufferedReader(new InputStreamReader(c.getInputStream()));

                out.println("first");
                System.out.printf("%n  request 1 -> %s%n", in.readLine());

                System.out.println("  ... connection sits idle for 900 ms (pool holds it) ...");
                Thread.sleep(900);

                System.out.printf("  socket says isConnected=%s isClosed=%s  <-- it still looks fine%n",
                        c.isConnected(), c.isClosed());

                out.println("second");
                String reply = in.readLine();
                System.out.printf("  request 2 -> %s%n", reply == null ? "NOTHING — the peer was already gone" : reply);
            } catch (SocketException e) {
                System.out.printf("  request 2 -> failed: %s%n", e.getMessage());
            }
        }

        System.out.println("""

                  Two things worth keeping.

                  First, `isConnected()` is a lie of omission: it reports whether this
                  socket was EVER connected, not whether the peer is still there. TCP
                  has no dial tone — a connection with no traffic on it is
                  indistinguishable from a dead one until you try to use it.

                  Second, this failure always lands on the NEXT request, which means
                  it lands on a random unlucky user rather than on the idle period
                  that caused it. That is why pools need an idle-eviction timeout set
                  BELOW the shortest idle timeout of anything between you and the
                  server.""");
    }

    // ------------------------------------------------------------------
    // 6 — TCP gives you a stream; you wanted messages
    // ------------------------------------------------------------------
    static void exp6_aStreamNotMessages() throws Exception {
        banner("EXPERIMENT 6 — three writes, and what actually arrives");
        System.out.println("""
                You already met framing in the io-multiplexing lab. This shows the
                socket-level reason it is unavoidable: the sending side's write
                boundaries are not preserved.""");

        try (ServerSocket srv = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                try (Socket c = new Socket("127.0.0.1", srv.getLocalPort());
                     OutputStream o = c.getOutputStream()) {
                    o.write("ALPHA".getBytes()); o.flush();
                    o.write("BETA".getBytes());                 // no flush — may coalesce
                    o.write("GAMMA".getBytes()); o.flush();
                    Thread.sleep(150);
                } catch (Exception ignored) { }
            });

            try (Socket c = srv.accept(); InputStream in = c.getInputStream()) {
                c.setSoTimeout(700);
                byte[] buf = new byte[64];
                int reads = 0;
                System.out.println();
                try {
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        reads++;
                        System.out.printf("  read #%d returned %2d bytes: %s%n",
                                reads, r, new String(buf, 0, r));
                    }
                } catch (SocketTimeoutException expected) { }
                System.out.printf("%n  the sender made 3 writes; the receiver needed %d read(s)%n", reads);
            }
        }

        System.out.println("""

                  There is no rule that says one write becomes one read. TCP is a
                  stream of bytes with no record boundaries, so the receiver must
                  impose its own structure — a length prefix, a delimiter, or a
                  self-describing format. "It worked in testing" usually means your
                  messages were small and your network was fast.""");
    }

    static void banner(String s) {
        System.out.println("\n" + "=".repeat(74));
        System.out.println(s);
        System.out.println("=".repeat(74));
    }
}
