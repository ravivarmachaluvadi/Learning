import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * MiniRedis -- a real TCP server that speaks real RESP, built to make Redis's one
 * central design decision visible and testable:
 *
 *     EVERY COMMAND RUNS TO COMPLETION ON ONE THREAD, ONE AT A TIME.
 *
 * That single sentence is simultaneously:
 *   - where atomicity comes from   (nothing can interleave, so INCR cannot lose
 *     an update, and no caller ever needs a lock)   -- see Experiment 1
 *   - where the worst failure mode comes from       (one slow command freezes
 *     every other client on the box)                -- see Experiment 2
 *
 * The event loop below is the same Selector machinery as the io-multiplexing lab:
 * hand the whole connection set to the kernel, sleep, get woken with the ready
 * subset. Redis is that lab's lesson applied to a database.
 *
 * Run it:
 *     java src/MiniRedis.java 6380
 *     java src/MiniRedis.java 6380 --parallel     (the deliberately broken variant)
 */
public final class MiniRedis {

    // ---------------- connection state ----------------

    static final class Conn {
        SocketChannel ch;
        SelectionKey key;                       // null in --parallel mode
        byte[] in = new byte[16384];
        int inLen;
        final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        ByteBuffer wbuf;

        boolean isReplicaLink;                  // this socket is a replica listening for our stream
        long ackOffset;

        boolean inMulti;                        // MULTI ... EXEC state
        boolean multiAborted;
        final List<List<String>> multiQueue = new ArrayList<>();
        final Map<String, Long> watched = new HashMap<>();
        final Set<String> subs = new HashSet<>();

        String name = "";
    }

    // ---------------- server state ----------------

    final int port;
    final boolean parallel;                     // true = the BROKEN "what if Redis were multi-threaded" mode
    final Store store = new Store();
    final Commands commands = new Commands(this);

    volatile boolean running;
    private Selector selector;
    private ServerSocketChannel ssc;
    private Thread loopThread;
    private ExecutorService parallelPool;
    final List<Conn> conns = Collections.synchronizedList(new ArrayList<>());
    final List<Conn> replicas = new ArrayList<>();

    // persistence (AOF -- "append only file", Redis's write-ahead log)
    String aofPath;
    private FileOutputStream aofOut;
    private final ByteArrayOutputStream aofBuf = new ByteArrayOutputStream();
    String appendfsync = "everysec";            // no | everysec | always
    long writtenLen, fsyncedLen;                // bytes handed to the OS vs bytes actually durable
    long lastFsyncMs;   // exposed so Experiment 6 can report how far into the fsync window the crash landed

    // replication
    long replOffset;
    int replDelayMs;                            // debug knob: hold the replica stream back
    private final List<Object[]> replPending = new ArrayList<>();  // {dueAtMs, byte[]}
    volatile boolean isReplica;
    private SocketChannel masterLink;
    private byte[] masterIn = new byte[65536];
    private int masterInLen;

    // observability
    final List<String> slowlog = Collections.synchronizedList(new ArrayList<>());
    long slowlogThresholdUs = 10_000;
    volatile long loopIterations, blockedMsTotal;
    volatile boolean activeExpireEnabled = true;
    final Map<String, Set<Conn>> channels = new HashMap<>();

    public MiniRedis(int port) { this(port, false); }
    public MiniRedis(int port, boolean parallel) { this.port = port; this.parallel = parallel; }

    // ---------------- lifecycle ----------------

    public void start() throws IOException {
        running = true;
        if (parallel) startParallel(); else startEventLoop();
    }

    private void startEventLoop() throws IOException {
        selector = Selector.open();
        ssc = ServerSocketChannel.open();
        ssc.configureBlocking(false);
        ssc.socket().setReuseAddress(true);
        ssc.bind(new InetSocketAddress("127.0.0.1", port));
        ssc.register(selector, SelectionKey.OP_ACCEPT);
        loopThread = new Thread(this::loop, "miniredis-" + port);
        loopThread.setDaemon(true);
        loopThread.start();
    }

    /**
     * THE MAIN LOOP. Accept, read, execute, reply, housekeep -- forever, on one thread.
     *
     * Note what is NOT here: no locks, no synchronized, no atomics around the store.
     * There cannot be a race because there is only ever one thread inside it. Every
     * command therefore observes the keyspace exactly as the previous command left it.
     */
    private void loop() {
        while (running) {
            try {
                loopIterations++;
                selector.select(5);
                Set<SelectionKey> ready = selector.selectedKeys();
                for (Iterator<SelectionKey> it = ready.iterator(); it.hasNext(); ) {
                    SelectionKey k = it.next(); it.remove();
                    if (!k.isValid()) continue;
                    if (k.isAcceptable()) doAccept();
                    else {
                        Conn c = (Conn) k.attachment();
                        if (k.isReadable()) doRead(c);
                        if (k.isValid() && k.isWritable()) flushOut(c);
                    }
                }
                if (isReplica) pumpMasterLink();
                cron();
            } catch (ClosedSelectorException e) {
                break;
            } catch (Throwable t) {
                if (running) t.printStackTrace();
            }
        }
    }

    /** Housekeeping Redis calls serverCron: expire, evict, flush the AOF, feed replicas. */
    private void cron() {
        if (activeExpireEnabled) store.activeExpireCycle(16);
        store.evictIfNeeded();
        flushAof();
        drainReplPending();
    }

    // ---------------- the deliberately broken variant ----------------

    /**
     * --parallel: one blocking thread per connection, all sharing ONE unsynchronised
     * Store. This is what "just make Redis multi-threaded" naively means. It is here
     * for exactly one purpose: to measure what the single thread was buying you.
     * Experiment 1 runs the same workload against both and counts the lost updates.
     */
    private void startParallel() throws IOException {
        ssc = ServerSocketChannel.open();
        ssc.socket().setReuseAddress(true);
        ssc.bind(new InetSocketAddress("127.0.0.1", port));
        parallelPool = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
        loopThread = new Thread(() -> {
            while (running) {
                try {
                    SocketChannel ch = ssc.accept();
                    if (ch == null) continue;
                    Conn c = new Conn(); c.ch = ch; conns.add(c);
                    parallelPool.submit(() -> serveBlocking(c));
                } catch (IOException e) { if (running) return; }
            }
        }, "miniredis-par-" + port);
        loopThread.setDaemon(true);
        loopThread.start();
    }

    private void serveBlocking(Conn c) {
        try {
            ByteBuffer buf = ByteBuffer.allocate(8192);
            while (running && c.ch.isOpen()) {
                buf.clear();
                int n = c.ch.read(buf);
                if (n < 0) break;
                if (n == 0) continue;
                appendIn(c, buf.array(), n);
                drainCommands(c);
                flushOut(c);
            }
        } catch (IOException ignored) {
        } finally { closeConn(c); }
    }

    // ---------------- socket plumbing ----------------

    private void doAccept() throws IOException {
        SocketChannel ch = ssc.accept();
        if (ch == null) return;
        ch.configureBlocking(false);
        ch.socket().setTcpNoDelay(true);
        Conn c = new Conn();
        c.ch = ch;
        c.key = ch.register(selector, SelectionKey.OP_READ, c);
        conns.add(c);
    }

    private final ByteBuffer readBuf = ByteBuffer.allocate(65536);

    private void doRead(Conn c) {
        try {
            readBuf.clear();
            int n = c.ch.read(readBuf);
            if (n < 0) { closeConn(c); return; }
            if (n == 0) return;
            appendIn(c, readBuf.array(), n);
            drainCommands(c);
            flushOut(c);
        } catch (IOException e) { closeConn(c); }
    }

    private void appendIn(Conn c, byte[] src, int n) {
        if (c.inLen + n > c.in.length) c.in = Arrays.copyOf(c.in, Math.max(c.in.length * 2, c.inLen + n));
        System.arraycopy(src, 0, c.in, c.inLen, n);
        c.inLen += n;
    }

    /**
     * FRAMING (io-multiplexing lab, made concrete): pull as many COMPLETE commands out
     * of the buffer as are there, and leave any partial tail alone. Pipelining -- the
     * single biggest real-world Redis speedup -- is nothing more than this loop running
     * more than once per read. See Experiment 3.
     */
    private void drainCommands(Conn c) {
        int off = 0;
        while (true) {
            Resp.Parsed p = Resp.parse(c.in, off, c.inLen);
            if (p == null) break;
            off += p.consumed();
            if (!p.args().isEmpty()) execute(c, p.args());
        }
        if (off > 0) {
            System.arraycopy(c.in, off, c.in, 0, c.inLen - off);
            c.inLen -= off;
        }
    }

    void queueOut(Conn c, byte[] b) {
        if (c.isReplicaLink && !isReplica) { /* replicas get the stream, not replies */ }
        synchronized (c.pending) { c.pending.writeBytes(b); }
    }

    void flushOut(Conn c) {
        try {
            synchronized (c.pending) {
                if (c.wbuf == null || !c.wbuf.hasRemaining()) {
                    if (c.pending.size() == 0) {
                        if (c.key != null && c.key.isValid()) c.key.interestOps(SelectionKey.OP_READ);
                        return;
                    }
                    c.wbuf = ByteBuffer.wrap(c.pending.toByteArray());
                    c.pending.reset();
                }
            }
            c.ch.write(c.wbuf);
            if (c.key != null && c.key.isValid())
                c.key.interestOps(c.wbuf.hasRemaining() ? (SelectionKey.OP_READ | SelectionKey.OP_WRITE)
                                                        : SelectionKey.OP_READ);
            else while (c.wbuf.hasRemaining()) c.ch.write(c.wbuf);   // blocking mode
        } catch (IOException e) { closeConn(c); }
    }

    void closeConn(Conn c) {
        try { if (c.key != null) c.key.cancel(); c.ch.close(); } catch (IOException ignored) {}
        conns.remove(c);
        replicas.remove(c);
        synchronized (channels) { for (Set<Conn> s : channels.values()) s.remove(c); }
    }

    // ---------------- command execution ----------------

    /**
     * The dispatcher. Everything a command needs to be safe happens because of WHERE
     * this method is called from (the one loop thread), not because of anything in it.
     */
    void execute(Conn c, List<String> args) {
        long t0 = System.nanoTime();
        String name = args.get(0).toUpperCase(Locale.ROOT);

        // MULTI: queue instead of run. The commands are not executed yet -- they are
        // held until EXEC, which then runs the whole batch with nothing interleaved.
        if (c.inMulti && !name.equals("EXEC") && !name.equals("DISCARD")
                      && !name.equals("MULTI") && !name.equals("WATCH")) {
            if (!commands.exists(name)) { c.multiAborted = true; queueOut(c, Resp.error("ERR unknown command '" + args.get(0) + "'")); return; }
            c.multiQueue.add(args);
            queueOut(c, Resp.simple("QUEUED"));
            return;
        }

        long dirtyBefore = store.dirty;
        byte[] reply;
        try {
            reply = commands.run(c, name, args);
        } catch (Throwable t) {
            reply = Resp.error("ERR " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        store.commandsProcessed++;

        // PROPAGATE THE EFFECT, NOT THE REQUEST. Only if the command actually changed
        // something does it reach the AOF and the replicas -- a SET NX that lost the
        // race must not be replayed as if it had won.
        if (store.dirty != dirtyBefore) {
            List<String> toPropagate = commands.takePropagateOverride(args);
            byte[] enc = Resp.command(toPropagate);
            appendAof(enc);
            propagate(enc);
        }

        if (reply != null && !c.isReplicaLink) queueOut(c, reply);

        long us = (System.nanoTime() - t0) / 1000;
        if (us >= slowlogThresholdUs)
            slowlog.add(us + "us " + String.join(" ", args.subList(0, Math.min(4, args.size()))));
    }

    // ---------------- persistence ----------------

    /**
     * Three separate moments, and confusing them is the classic production mistake:
     *   1. append to an in-memory buffer   -- what appendAof() does
     *   2. write() it to the OS            -- flushAof(): now a `kill -9` CANNOT lose it,
     *                                         because the kernel's page cache holds it
     *   3. fsync() it to the disk          -- only now does a POWER CUT not lose it
     *
     * `appendfsync everysec` (the default) does step 3 about once a second, which is
     * exactly why Redis's honest durability promise is "up to one second of
     * acknowledged writes may be lost". Experiment 6 measures both boundaries.
     */
    void appendAof(byte[] enc) {
        if (aofPath == null) return;
        aofBuf.writeBytes(enc);
    }

    void flushAof() {
        if (aofPath == null) return;
        try {
            if (aofOut == null) aofOut = new FileOutputStream(aofPath, true);
            if (aofBuf.size() > 0) {
                byte[] b = aofBuf.toByteArray(); aofBuf.reset();
                aofOut.write(b);                 // step 2: into the OS page cache
                writtenLen += b.length;
            }
            long now = System.currentTimeMillis();
            boolean doSync = switch (appendfsync) {
                case "always"  -> true;
                case "everysec"-> now - lastFsyncMs >= 1000;
                default        -> false;
            };
            if (doSync && writtenLen > fsyncedLen) {
                aofOut.getFD().sync();           // step 3: actually durable
                fsyncedLen = writtenLen;
                lastFsyncMs = now;
            }
        } catch (IOException e) { /* a real server would stop accepting writes here */ }
    }

    /** Load an AOF back into the keyspace -- Redis's recovery path, replayed command by command. */
    public int loadAof() throws IOException {
        if (aofPath == null || !Files.exists(Path.of(aofPath))) return 0;
        byte[] all = Files.readAllBytes(Path.of(aofPath));
        int off = 0, n = 0;
        Conn fake = new Conn();
        while (true) {
            Resp.Parsed p = Resp.parse(all, off, all.length);
            if (p == null) break;
            off += p.consumed();
            if (p.args().isEmpty()) continue;
            String nm = p.args().get(0).toUpperCase(Locale.ROOT);
            try { commands.run(fake, nm, p.args()); n++; } catch (Throwable ignored) {}
        }
        writtenLen = fsyncedLen = all.length;
        return n;
    }

    /**
     * Simulated POWER LOSS (not a process kill): everything write()n but not fsync()ed
     * is gone, because that is precisely what the page cache loses when the machine
     * stops. Modelled by truncating the file back to the last fsynced byte.
     */
    public void crashPowerLoss() {
        running = false;
        try {
            if (aofOut != null) aofOut.close();
            if (aofPath != null) try (FileChannel fc = FileChannel.open(Path.of(aofPath), StandardOpenOption.WRITE)) {
                fc.truncate(fsyncedLen);
            }
        } catch (IOException ignored) {}
        hardClose();
    }

    public void stop() {
        running = false;
        try { flushAof(); if (aofOut != null) { aofOut.getFD().sync(); aofOut.close(); } } catch (IOException ignored) {}
        hardClose();
    }

    private void hardClose() {
        try { if (selector != null) selector.close(); } catch (IOException ignored) {}
        try { if (ssc != null) ssc.close(); } catch (IOException ignored) {}
        for (Conn c : new ArrayList<>(conns)) { try { c.ch.close(); } catch (IOException ignored) {} }
        conns.clear(); replicas.clear();
        if (parallelPool != null) parallelPool.shutdownNow();
    }

    // ---------------- replication ----------------

    /** Everything a write command changed is streamed to every attached replica. ASYNCHRONOUSLY. */
    void propagate(byte[] enc) {
        if (replicas.isEmpty()) return;
        replOffset += enc.length;
        if (replDelayMs > 0) {
            synchronized (replPending) { replPending.add(new Object[]{System.currentTimeMillis() + replDelayMs, enc}); }
        } else {
            for (Conn r : new ArrayList<>(replicas)) { queueOut(r, enc); flushOut(r); }
        }
    }

    private void drainReplPending() {
        synchronized (replPending) {
            long now = System.currentTimeMillis();
            Iterator<Object[]> it = replPending.iterator();
            while (it.hasNext()) {
                Object[] e = it.next();
                if ((Long) e[0] > now) break;
                it.remove();
                for (Conn r : new ArrayList<>(replicas)) { queueOut(r, (byte[]) e[1]); flushOut(r); }
            }
        }
    }

    /** A replica has just said SYNC: send the whole keyspace, then keep streaming. */
    void doSync(Conn c) {
        for (String k : store.liveKeys()) {
            Store.Value v = store.dict.get(k);
            if (v == null) continue;
            for (List<String> cmd : commands.dumpValue(k, v)) queueOut(c, Resp.command(cmd));
            Long d = store.expiryOf(k);
            if (d != null) queueOut(c, Resp.command(List.of("PEXPIREAT", k, String.valueOf(d))));
        }
        c.isReplicaLink = true;
        replicas.add(c);
        flushOut(c);
    }

    /** This node becomes a replica of host:port. */
    public void replicaOf(String host, int p) throws IOException {
        masterLink = SocketChannel.open(new InetSocketAddress(host, p));
        masterLink.configureBlocking(false);
        masterLink.write(ByteBuffer.wrap(Resp.command(List.of("SYNC"))));
        isReplica = true;
    }

    /** Apply whatever the master has streamed to us. A replica is an AOF-replayer over a socket. */
    private void pumpMasterLink() {
        if (masterLink == null) return;
        try {
            ByteBuffer b = ByteBuffer.allocate(65536);
            int n = masterLink.read(b);
            if (n <= 0) return;
            if (masterInLen + n > masterIn.length) masterIn = Arrays.copyOf(masterIn, (masterInLen + n) * 2);
            System.arraycopy(b.array(), 0, masterIn, masterInLen, n);
            masterInLen += n;
            int off = 0;
            Conn fake = new Conn();
            while (true) {
                Resp.Parsed p = Resp.parse(masterIn, off, masterInLen);
                if (p == null) break;
                off += p.consumed();
                if (p.args().isEmpty()) continue;
                try { commands.run(fake, p.args().get(0).toUpperCase(Locale.ROOT), p.args()); } catch (Throwable ignored) {}
            }
            if (off > 0) { System.arraycopy(masterIn, off, masterIn, 0, masterInLen - off); masterInLen -= off; }
        } catch (IOException ignored) {}
    }

    /** Stop following the master and start serving writes. This is what a failover does. */
    public void promote() { isReplica = false; try { if (masterLink != null) masterLink.close(); } catch (IOException ignored) {} masterLink = null; }

    // ---------------- entry point ----------------

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 6380;
        boolean par = Arrays.asList(args).contains("--parallel");
        MiniRedis s = new MiniRedis(port, par);
        for (int i = 0; i < args.length; i++) if (args[i].equals("--aof") && i + 1 < args.length) s.aofPath = args[i + 1];
        if (s.aofPath != null) System.out.println("loaded " + s.loadAof() + " commands from AOF " + s.aofPath);
        s.start();
        System.out.println("MiniRedis listening on 127.0.0.1:" + port
                + (par ? "  [--parallel: UNSAFE, commands run concurrently]" : "  [single-threaded]")
                + "\nTry:  java src/Cli.java " + port + "\n      nc 127.0.0.1 " + port);
        Runtime.getRuntime().addShutdownHook(new Thread(s::stop));
        Thread.currentThread().join();
    }
}
