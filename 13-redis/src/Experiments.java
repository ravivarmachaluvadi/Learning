import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Nine experiments that break MiniRedis on purpose. Each one is a claim in the
 * README turned into something you can watch happen.
 *
 *     java src/Experiments.java        run all
 *     java src/Experiments.java 2      run just experiment 2
 */
public final class Experiments {

    static final AtomicInteger PORT = new AtomicInteger(7501);

    public static void main(String[] args) throws Exception {
        Set<Integer> only = new HashSet<>();
        for (String a : args) only.add(Integer.parseInt(a));
        List<Runner> all = List.of(
            new Runner(1, "Where atomicity actually comes from",        Experiments::e1),
            new Runner(2, "One slow command freezes every client",      Experiments::e2),
            new Runner(3, "The round trip is the cost, not the work",   Experiments::e3),
            new Runner(4, "Expired is not the same as deleted",         Experiments::e4),
            new Runner(5, "Sampled LRU vs exact LRU vs random",         Experiments::e5),
            new Runner(6, "How much does a crash actually lose?",       Experiments::e6),
            new Runner(7, "Async replication loses acknowledged writes",Experiments::e7),
            new Runner(8, "The distributed lock that lets two in",      Experiments::e8),
            new Runner(9, "WATCH retries vs one atomic command",        Experiments::e9));
        for (Runner r : all) {
            if (!only.isEmpty() && !only.contains(r.n)) continue;
            System.out.println("\n" + "=".repeat(74));
            System.out.printf("EXPERIMENT %d -- %s%n", r.n, r.title);
            System.out.println("=".repeat(74));
            try { r.body.run(); } catch (Throwable t) { t.printStackTrace(); }
        }
        System.out.println();
    }

    record Runner(int n, String title, Body body) {}
    interface Body { void run() throws Exception; }

    // ================================================================= 1

    /**
     * PREDICT FIRST: eight clients each send 2,000 INCR to the same key. Nobody takes a
     * lock. What is the final value on the single-threaded server? On a server that runs
     * the same commands on many threads?
     */
    static void e1() throws Exception {
        int threads = 8, per = 2000, expected = threads * per;

        long single = incrRace(false, threads, per);
        long many   = incrRace(true,  threads, per);

        System.out.printf("%n  expected                                : %,d%n", expected);
        System.out.printf("  single-threaded server (real Redis model): %,d   -> %,d lost%n", single, expected - single);
        System.out.printf("  same commands on many threads           : %,d   -> %,d lost (%.1f%%)%n",
                many, expected - many, 100.0 * (expected - many) / expected);
        System.out.print(("""
        
          Nothing in the command's code changed between those two runs. The only
          difference is how many threads were allowed inside the keyspace. Redis's
          atomicity is not a feature it implements -- it is concurrency it refuses
          to have. That is the trade the whole design is built on.""").indent(2));
    }

    static long incrRace(boolean parallel, int threads, int per) throws Exception {
        MiniRedis s = new MiniRedis(PORT.getAndIncrement(), parallel);
        s.start();
        try {
            try (Cli warm = new Cli(s.port)) { warm.cmd("SET", "counter", "0"); }
            concurrently(threads, t -> {
                try (Cli c = new Cli(s.port)) {
                    List<String[]> batch = new ArrayList<>();
                    for (int i = 0; i < per; i++) batch.add(new String[]{"INCR", "counter"});
                    for (int i = 0; i < per; i += 100) c.pipeline(batch.subList(i, Math.min(per, i + 100)));
                } catch (Exception e) { /* the unsafe server can genuinely blow up */ }
            });
            Thread.sleep(150);
            try (Cli c = new Cli(s.port)) {
                Object v = c.cmd("GET", "counter");
                return v == null ? 0 : Long.parseLong((String) v);
            }
        } finally { s.stop(); }
    }

    // ================================================================= 2

    /**
     * PREDICT FIRST: twenty clients are doing tiny GETs. One other client runs KEYS *
     * against 200,000 keys. What happens to the twenty innocent clients?
     */
    static void e2() throws Exception {
        MiniRedis s = new MiniRedis(PORT.getAndIncrement());
        s.start();
        try (Cli loader = new Cli(s.port)) {
            List<String[]> batch = new ArrayList<>();
            for (int i = 0; i < 200_000; i++) {
                batch.add(new String[]{"SET", "user:session:" + i, "v"});
                if (batch.size() == 5000) { loader.pipeline(batch); batch.clear(); }
            }
            if (!batch.isEmpty()) loader.pipeline(batch);
            System.out.printf("  loaded %,d keys%n", 200_000);

            AtomicBoolean run = new AtomicBoolean(true);
            List<Long> baseline = Collections.synchronizedList(new ArrayList<>());
            List<Long> during   = Collections.synchronizedList(new ArrayList<>());
            AtomicBoolean slowRunning = new AtomicBoolean(false);

            List<Thread> readers = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                Thread t = Thread.ofVirtual().start(() -> {
                    try (Cli c = new Cli(s.port)) {
                        while (run.get()) {
                            long t0 = System.nanoTime();
                            c.cmd("GET", "user:session:1");
                            long us = (System.nanoTime() - t0) / 1000;
                            (slowRunning.get() ? during : baseline).add(us);
                            Thread.sleep(1);
                        }
                    } catch (Exception ignored) {}
                });
                readers.add(t);
            }
            Thread.sleep(800);
            int baseN = baseline.size();

            slowRunning.set(true);
            long t0 = System.currentTimeMillis();
            try (Cli slow = new Cli(s.port)) { slow.cmd("KEYS", "user:session:1*"); }
            long keysMs = System.currentTimeMillis() - t0;
            slowRunning.set(false);
            Thread.sleep(200);
            run.set(false);
            for (Thread t : readers) t.join();

            System.out.printf("%n  the KEYS command itself took            : %,d ms%n", keysMs);
            System.out.printf("  innocent GETs measured before it        : %,d samples, p99 = %,d us%n", baseN, pct(baseline, 99));
            System.out.printf("  innocent GETs measured during it        : %,d samples, p99 = %,d us, max = %,d us%n",
                    during.size(), pct(during, 99), during.isEmpty() ? 0 : Collections.max(during));
            System.out.print(("""
            
              Those twenty clients did nothing wrong and were not talking to the same
              key. They queued behind one O(N) command because there is one thread and
              it runs each command to completion. This is the io-multiplexing lab's
              shared-fate problem with the stakes raised: on a real instance, KEYS * is
              how a healthy Redis takes an entire product down for several seconds.
              SLOWLOG is where you find the culprit afterwards.""").indent(2));
            try (Cli c = new Cli(s.port)) {
                @SuppressWarnings("unchecked") List<Object> sl = (List<Object>) c.cmd("SLOWLOG", "GET");
                System.out.println("\n  SLOWLOG says: " + (sl.isEmpty() ? "(empty)" : sl.get(sl.size() - 1)));
            }
        } finally { s.stop(); }
    }

    // ================================================================= 3

    /**
     * PREDICT FIRST: 10,000 increments, four ways. Which is fastest, and by how much?
     * (And which of them is also WRONG?)
     */
    static void e3() throws Exception {
        MiniRedis s = new MiniRedis(PORT.getAndIncrement());
        s.start();
        int n = 10_000;
        try {
            // (a) client-side read-modify-write: two round trips per increment, and racy
            long a; try (Cli c = new Cli(s.port)) {
                c.cmd("SET", "a", "0");
                long t0 = System.nanoTime();
                for (int i = 0; i < n; i++) {
                    long cur = Long.parseLong((String) c.cmd("GET", "a"));
                    c.cmd("SET", "a", String.valueOf(cur + 1));
                }
                a = (System.nanoTime() - t0) / 1_000_000;
            }
            // (b) one command, one round trip each
            long b; try (Cli c = new Cli(s.port)) {
                long t0 = System.nanoTime();
                for (int i = 0; i < n; i++) c.cmd("INCR", "b");
                b = (System.nanoTime() - t0) / 1_000_000;
            }
            // (c) pipelined: same commands, but do not wait for each reply
            long cc; try (Cli c = new Cli(s.port)) {
                List<String[]> batch = new ArrayList<>();
                for (int i = 0; i < n; i++) batch.add(new String[]{"INCR", "c"});
                long t0 = System.nanoTime();
                for (int i = 0; i < n; i += 1000) c.pipeline(batch.subList(i, Math.min(n, i + 1000)));
                cc = (System.nanoTime() - t0) / 1_000_000;
            }
            // (d) MULTI/EXEC -- also one round trip, but it is about grouping, not speed
            long d; try (Cli c = new Cli(s.port)) {
                long t0 = System.nanoTime();
                for (int chunk = 0; chunk < n / 1000; chunk++) {
                    List<String[]> batch = new ArrayList<>();
                    batch.add(new String[]{"MULTI"});
                    for (int i = 0; i < 1000; i++) batch.add(new String[]{"INCR", "d"});
                    batch.add(new String[]{"EXEC"});
                    c.pipeline(batch);
                }
                d = (System.nanoTime() - t0) / 1_000_000;
            }
            System.out.printf("%n  (a) GET + SET per increment, 20,000 round trips : %,5d ms   <- and RACY%n", a);
            System.out.printf("  (b) INCR, 10,000 round trips                   : %,5d ms   (%.1fx faster than a)%n", b, a / (double) b);
            System.out.printf("  (c) INCR pipelined, 10 round trips             : %,5d ms   (%.1fx faster than b)%n", cc, b / (double) cc);
            System.out.printf("  (d) MULTI/EXEC batches of 1000                 : %,5d ms%n", d);
            System.out.print(("""
            
              Two different lessons sit in that table and they are easy to confuse.
              (a) -> (b) is a CORRECTNESS win: the server-side command closes the gap
                    where another client could interleave. Speed is a side effect.
              (b) -> (c) is purely a NETWORK win: identical commands, identical
                    keyspace work, but the client stopped waiting for each reply.
              (d) is not a speed feature at all. MULTI/EXEC exists to group commands
                    so nothing interleaves; here it merely happens to pipeline too.""").indent(2));
        } finally { s.stop(); }
    }

    // ================================================================= 4

    /**
     * PREDICT FIRST: 50,000 keys with a 1-second TTL. One second later, nobody has
     * touched them. How much memory has Redis given back?
     */
    static void e4() throws Exception {
        MiniRedis s = new MiniRedis(PORT.getAndIncrement());
        s.start();
        try (Cli c = new Cli(s.port)) {
            c.cmd("DEBUG", "SET-ACTIVE-EXPIRE", "0");          // isolate lazy expiry first
            List<String[]> batch = new ArrayList<>();
            for (int i = 0; i < 50_000; i++) batch.add(new String[]{"SET", "s:" + i, "x".repeat(80), "PX", "1000"});
            for (int i = 0; i < batch.size(); i += 5000) c.pipeline(batch.subList(i, Math.min(batch.size(), i + 5000)));

            long memBefore = info(c, "used_memory");
            System.out.printf("  50,000 keys, TTL 1s   DBSIZE=%s  used_memory=%,d bytes%n", c.cmd("DBSIZE"), memBefore);

            c.cmd("DEBUG", "ADVANCE", "3000");                  // the deadlines have all passed
            System.out.printf("%n  3 seconds later, with the background cycle OFF:%n");
            System.out.printf("    DBSIZE (residency)      : %s      <- unchanged%n", c.cmd("DBSIZE"));
            System.out.printf("    logically alive         : %s%n", c.cmd("DEBUG", "LIVE"));

            // DEBUG LIVE walked every key, which lazily reaped them. Rebuild to show the
            // background cycle doing the same job without anyone reading anything.
            for (int i = 0; i < 50_000; i++) batch.set(i, new String[]{"SET", "t:" + i, "x".repeat(80), "PX", "1000"});
            for (int i = 0; i < batch.size(); i += 5000) c.pipeline(batch.subList(i, Math.min(batch.size(), i + 5000)));
            c.cmd("DEBUG", "ADVANCE", "3000");
            long residentBefore = (Long) c.cmd("DEBUG", "RESIDENT");
            int rounds = 0;
            while ((Long) c.cmd("DEBUG", "RESIDENT") > 0 && rounds < 4000) { c.cmd("DEBUG", "EXPIRECYCLE"); rounds++; }
            System.out.printf("%n  with the background cycle ON, nobody reading:%n");
            System.out.printf("    started at %,d resident, needed %,d sampling passes to reach 0%n", residentBefore, rounds);
            System.out.printf("    used_memory now         : %,d bytes%n", info(c, "used_memory"));
            System.out.print(("""
            
              Redis has no timer per key -- that would cost memory and CPU per key. It
              finds dead keys two ways: when someone touches one (lazy), and by sampling
              20 random keys with deadlines ~10 times a second and repeating while more
              than a quarter of the sample is dead (active). Both are cheap; neither is
              prompt. So "the TTL passed" and "the memory came back" are separate
              events, sometimes far apart, and an instance can sit at its maxmemory
              limit holding nothing but corpses.""").indent(2));
        } finally { s.stop(); }
    }

    // ================================================================= 5

    /**
     * PREDICT FIRST: Redis does not maintain a true LRU order -- it samples 5 keys and
     * evicts the oldest of those. How much hit ratio does that shortcut cost?
     */
    static void e5() throws Exception {
        System.out.println("  workload: 200,000 requests over 20,000 keys, 80% of traffic on the hot 20%");
        System.out.println("  cache sized to hold roughly a quarter of the keyspace\n");
        System.out.printf("  %-26s %10s %10s%n", "policy", "hit ratio", "evictions");
        for (String policy : List.of("allkeys-exactlru", "allkeys-lru", "allkeys-lfu", "allkeys-random")) {
            for (int samples : policy.equals("allkeys-lru") ? List.of(5, 10) : List.of(5)) {
                double[] r = evictionRun(policy, samples);
                String label = policy + (policy.equals("allkeys-lru") ? " (samples=" + samples + ")" : "");
                System.out.printf("  %-26s %9.1f%% %10.0f%n", label, r[0] * 100, r[1]);
            }
        }
        System.out.print(("""
        
          Exact LRU is the ceiling, and it is the one Redis refuses to implement: a true
          LRU order costs two pointers on every key forever, memory spent on bookkeeping
          instead of data. Sampling five keys gets most of the way there for free, and
          raising maxmemory-samples buys back a little more at a little more CPU. Random
          is the floor and shows how much the policy is doing at all.
          (LFU's number here is a floor, not its true value -- this run is short and LFU
          needs a long history plus decay to show its strength. The caching lab measured
          that properly.)""").indent(2));
    }

    static double[] evictionRun(String policy, int samples) throws Exception {
        MiniRedis s = new MiniRedis(PORT.getAndIncrement());
        s.start();
        try (Cli c = new Cli(s.port)) {
            int keyspace = 20_000, requests = 200_000;
            c.cmd("CONFIG", "SET", "maxmemory-policy", policy);
            c.cmd("CONFIG", "SET", "maxmemory-samples", String.valueOf(samples));
            for (int i = 0; i < keyspace / 4; i++) c.cmd("SET", "k" + i, "v");
            long quarter = info(c, "used_memory");
            c.cmd("CONFIG", "SET", "maxmemory", String.valueOf(quarter));

            Random rnd = new Random(7);
            int hits = 0;
            List<String[]> batch = new ArrayList<>();
            long hitsBefore = info(c, "keyspace_hits"), missBefore = info(c, "keyspace_misses");
            for (int i = 0; i < requests; i++) {
                int k = rnd.nextDouble() < 0.8 ? rnd.nextInt(keyspace / 5) : rnd.nextInt(keyspace);
                batch.add(new String[]{"GET", "k" + k});
                batch.add(new String[]{"SET", "k" + k, "v"});     // a miss refills, like a cache
                if (batch.size() >= 2000) { c.pipeline(batch); batch.clear(); }
            }
            if (!batch.isEmpty()) c.pipeline(batch);
            long h = info(c, "keyspace_hits") - hitsBefore, m = info(c, "keyspace_misses") - missBefore;
            return new double[]{h / (double) (h + m), info(c, "evicted_keys")};
        } finally { s.stop(); }
    }

    // ================================================================= 6

    /**
     * PREDICT FIRST: you run `kill -9` on a Redis with appendfsync set to "no" -- it has
     * not fsynced in ages. How much of the acknowledged data do you lose?
     */
    static void e6() throws Exception {
        System.out.println("  PART A -- a real `kill -9` on a real separate process, appendfsync no\n");
        Path aof = Files.createTempFile("miniredis-e6a", ".aof");
        Files.deleteIfExists(aof);
        int port = PORT.getAndIncrement();
        String javaBin = System.getProperty("java.home") + "/bin/java";
        Process p = new ProcessBuilder(javaBin, "src/MiniRedis.java", String.valueOf(port), "--aof", aof.toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        Thread.sleep(2500);
        int written = 0;
        try (Cli c = new Cli(port)) {
            c.cmd("CONFIG", "SET", "appendfsync", "no");
            for (int i = 0; i < 5000; i++) { c.cmd("SET", "k" + i, "v"); written++; }
            Thread.sleep(50);
        }
        Runtime.getRuntime().exec(new String[]{"kill", "-9", String.valueOf(p.pid())}).waitFor();
        p.waitFor(5, TimeUnit.SECONDS);
        MiniRedis recovered = new MiniRedis(PORT.getAndIncrement());
        recovered.aofPath = aof.toString();
        recovered.loadAof();
        int survived = recovered.store.dict.size();
        System.out.printf("    acknowledged writes : %,d%n    survived kill -9    : %,d   -> %,d lost%n",
                written, survived, written - survived);
        System.out.print(("""
        
            Nothing was lost, and appendfsync was OFF. The reason: Redis had already
            called write(), so the bytes were in the OPERATING SYSTEM's page cache, and
            killing the process does not empty the kernel's memory. appendfsync
            protects you from the machine dying, not from the process dying -- and
            those are very different events with very different frequencies.""").indent(2));
        Files.deleteIfExists(aof);

        System.out.println("\n  PART B -- simulated POWER LOSS (page cache gone), by fsync policy\n");
        System.out.printf("    %-12s %13s %10s %10s %16s %12s%n",
                "appendfsync", "acknowledged", "durable", "lost", "lost as traffic", "writes/sec");
        for (String policy : List.of("always", "everysec", "no")) {
            Path f = Files.createTempFile("miniredis-e6b", ".aof");
            Files.deleteIfExists(f);
            MiniRedis s = new MiniRedis(PORT.getAndIncrement());
            s.aofPath = f.toString(); s.appendfsync = policy;
            s.start();
            int acked = 0;
            long t0 = System.currentTimeMillis();
            try (Cli c = new Cli(s.port)) {
                long end = t0 + 4500;                 // several fsync intervals, ending MID-interval
                while (System.currentTimeMillis() < end) { c.cmd("SET", "k" + acked, "v"); acked++; }
            }
            long elapsed = System.currentTimeMillis() - t0;
            long sinceFsync = System.currentTimeMillis() - s.lastFsyncMs;
            s.crashPowerLoss();                        // the power goes out RIGHT HERE
            MiniRedis rec = new MiniRedis(PORT.getAndIncrement());
            rec.aofPath = f.toString(); rec.loadAof();
            int durable = rec.store.dict.size();
            long rate = acked * 1000L / Math.max(1, elapsed);
            System.out.printf("    %-12s %,13d %,10d %,10d %,13d ms %,12d%n",
                    policy, acked, durable, acked - durable,
                    (acked - durable) * 1000L / Math.max(1, rate), rate);
            if (policy.equals("everysec"))
                System.out.printf("    %-12s (the crash landed %,d ms into the 1000 ms fsync window)%n", "", sinceFsync);
            Files.deleteIfExists(f);
        }
        System.out.print(("""
        
            Read the `lost` column as a DURATION, not a quantity. `everysec` -- the
            default -- did not lose a fixed share of the writes; it lost everything
            written since its last fsync, which is why the loss is roughly one second
            of traffic however long the run was. That is Redis's actual durability
            promise, stated honestly: your write is acknowledged now and becomes
            durable up to a second from now, and a power cut inside that window erases
            data the client was told was safe.

            Now look at the writes/sec column, because that is the reason `everysec`
            is the default rather than `always`. Buying the zero in the lost column
            costs an fsync on the write path for every single command.

            And note what NONE of these columns can tell you: whether any OTHER MACHINE
            has the write. Durability and availability are separate losses. The second
            one is Experiment 7.""").indent(2));
    }

    // ================================================================= 7

    /**
     * PREDICT FIRST: the master replies OK to 500 writes, then dies. The replica is
     * promoted. How many of those 500 acknowledged writes are on the replica?
     */
    static void e7() throws Exception {
        for (int delay : new int[]{0, 1500}) {
            MiniRedis master = new MiniRedis(PORT.getAndIncrement());
            MiniRedis replica = new MiniRedis(PORT.getAndIncrement());
            master.start(); replica.start();
            int acked = 0;
            try (Cli mc = new Cli(master.port); Cli rc = new Cli(replica.port)) {
                rc.cmd("REPLICAOF", "127.0.0.1", String.valueOf(master.port));
                Thread.sleep(250);
                mc.cmd("DEBUG", "REPLDELAY", String.valueOf(delay));
                for (int i = 0; i < 500; i++) { mc.cmd("SET", "order:" + i, "paid"); acked++; }
                Thread.sleep(100);
            }
            master.crashPowerLoss();                 // the master is gone, mid-stream
            replica.promote();                       // a failover makes the replica the new master
            Thread.sleep(200);
            int present;
            try (Cli rc = new Cli(replica.port)) { present = (int)(long)(Long) rc.cmd("DBSIZE"); }
            System.out.printf("  replication lag %,5d ms  ->  acked %,d, on the promoted replica %,d, LOST %,d%n",
                    delay, acked, present, acked - present);
            replica.stop();
        }
        System.out.print(("""
        
          Both rows are the same code with the same guarantees. The only variable is how
          far behind the replica happened to be when the master died -- and you do not
          control that, the network and the master's load do.

          Redis replication is ASYNCHRONOUS: the master replies to the client and streams
          to replicas as two independent acts, in that order. So "Redis told me OK" and
          "this write will survive a failover" are different statements, and the gap
          between them is exactly the replication lag. This is the cap-theorem lab's AP
          corner, and it is not a misconfiguration -- it is the product.

          WAIT numreplicas timeout narrows the window by telling you how many replicas
          already have the write. It cannot widen it backwards: by the time WAIT returns
          a disappointing number, the client already has its OK. Redis has no rollback.""").indent(2));
    }

    // ================================================================= 8

    /**
     * PREDICT FIRST: client A takes a lock with a 200 ms deadline, then stalls for 500 ms
     * in a garbage-collection pause. How many clients believe they hold the lock?
     */
    static void e8() throws Exception {
        MiniRedis s = new MiniRedis(PORT.getAndIncrement());
        s.start();
        try {
            // The shared thing the lock is supposed to protect. It records who wrote.
            List<String> ledger = Collections.synchronizedList(new ArrayList<>());

            Thread a = Thread.ofVirtual().start(() -> {
                try (Cli c = new Cli(s.port)) {
                    if (!"OK".equals(c.cmd("SET", "lock:invoice", "A", "NX", "PX", "200"))) return;
                    System.out.println("  A: acquired the lock, deadline 200 ms");
                    Thread.sleep(500);                       // <- a GC pause, a slow disk, a scheduler hiccup
                    System.out.println("  A: woke up, still believes it holds the lock -> writing");
                    ledger.add("A");
                } catch (Exception ignored) {}
            });
            Thread.sleep(300);
            Thread b = Thread.ofVirtual().start(() -> {
                try (Cli c = new Cli(s.port)) {
                    if (!"OK".equals(c.cmd("SET", "lock:invoice", "B", "NX", "PX", "200"))) return;
                    System.out.println("  B: acquired the lock (A's had expired) -> writing");
                    ledger.add("B");
                } catch (Exception ignored) {}
            });
            a.join(); b.join();
            System.out.printf("%n  writers that entered the critical section: %d  %s%n", ledger.size(), ledger);

            System.out.print(("""
            
              Two. Redis did nothing wrong: it granted one lock, expired it on schedule,
              and granted it again. The lock was lost by A without A being told, because
              nothing can tell A -- A was not running. Any lock with a timeout has this
              hole, and a lock without a timeout deadlocks when a holder dies, so you
              cannot escape by removing the deadline.

              The fix does not live in Redis. It lives at the RESOURCE.""").indent(2));

            // Now with a fencing token: every acquisition gets a strictly larger number,
            // and the resource refuses anything below the highest number it has seen.
            Fenced resource = new Fenced();
            List<String> fencedLedger = Collections.synchronizedList(new ArrayList<>());
            try (Cli c = new Cli(s.port)) { c.cmd("DEL", "lock:invoice"); }

            Thread a2 = Thread.ofVirtual().start(() -> {
                try (Cli c = new Cli(s.port)) {
                    if (!"OK".equals(c.cmd("SET", "lock:invoice", "A", "NX", "PX", "200"))) return;
                    long token = (Long) c.cmd("INCR", "fence:invoice");   // monotonic, and atomic
                    Thread.sleep(500);
                    if (resource.write("A", token)) fencedLedger.add("A");
                    else System.out.println("  A: REJECTED by the resource -- token " + token + " is stale");
                } catch (Exception ignored) {}
            });
            Thread.sleep(300);
            Thread b2 = Thread.ofVirtual().start(() -> {
                try (Cli c = new Cli(s.port)) {
                    if (!"OK".equals(c.cmd("SET", "lock:invoice", "B", "NX", "PX", "200"))) return;
                    long token = (Long) c.cmd("INCR", "fence:invoice");
                    if (resource.write("B", token)) fencedLedger.add("B");
                } catch (Exception ignored) {}
            });
            a2.join(); b2.join();
            System.out.printf("%n  with fencing tokens, writers accepted: %d  %s%n", fencedLedger.size(), fencedLedger);
            System.out.print(("""
            
              The lock still failed in exactly the same way -- both clients still thought
              they held it. What changed is that the RESOURCE can now tell which belief is
              stale, because every acquisition carries a strictly increasing number and
              the resource remembers the highest it has honoured.

              So: a Redis lock is a good way to stop two workers doing the same job twice.
              It is not, on its own, a way to guarantee they cannot. If correctness depends
              on it, the protected resource must reject stale writers itself.""").indent(2));
        } finally { s.stop(); }
    }

    /** A resource that refuses to be written by a holder whose lock has been superseded. */
    static final class Fenced {
        private long highestSeen = 0;
        synchronized boolean write(String who, long token) {
            if (token < highestSeen) return false;
            highestSeen = token;
            System.out.println("  " + who + ": accepted with token " + token);
            return true;
        }
    }

    // ================================================================= 9

    /**
     * PREDICT FIRST: 12 clients each add 200 to one counter. One group uses
     * WATCH/MULTI/EXEC with retries; the other uses INCRBY. Same answer -- but at what cost?
     */
    static void e9() throws Exception {
        MiniRedis s = new MiniRedis(PORT.getAndIncrement());
        s.start();
        try {
            int threads = 12, per = 200;
            AtomicInteger retries = new AtomicInteger();
            try (Cli c = new Cli(s.port)) { c.cmd("SET", "w", "0"); c.cmd("SET", "i", "0"); }

            long t0 = System.currentTimeMillis();
            concurrently(threads, t -> {
                try (Cli c = new Cli(s.port)) {
                    for (int n = 0; n < per; n++) {
                        while (true) {
                            c.cmd("WATCH", "w");
                            long cur = Long.parseLong((String) c.cmd("GET", "w"));
                            c.cmd("MULTI");
                            c.cmd("SET", "w", String.valueOf(cur + 1));
                            if (c.cmd("EXEC") != null) break;      // nil means somebody beat us
                            retries.incrementAndGet();
                        }
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            long watchMs = System.currentTimeMillis() - t0;

            t0 = System.currentTimeMillis();
            concurrently(threads, t -> {
                try (Cli c = new Cli(s.port)) { for (int n = 0; n < per; n++) c.cmd("INCR", "i"); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            long incrMs = System.currentTimeMillis() - t0;

            try (Cli c = new Cli(s.port)) {
                System.out.printf("%n  WATCH/MULTI/EXEC : result=%s  %,d ms, %,d aborted attempts (%.1f per success)%n",
                        c.cmd("GET", "w"), watchMs, retries.get(), retries.get() / (double)(threads * per));
                System.out.printf("  INCR             : result=%s  %,d ms, 0 aborted attempts%n", c.cmd("GET", "i"), incrMs);
            }
            System.out.print(("""
            
              Both are correct. WATCH is optimistic concurrency -- the expected-version
              check from the event-sourcing lab, applied per key -- and like all optimism
              it gets more expensive the more contended the key is, because every loser
              has to redo its work.

              The rule this gives you: if a single command already expresses what you
              want, use it, because it cannot lose a race and cannot need a retry. Reach
              for WATCH only when your change depends on a value in a way no single
              command captures -- and reach for a Lua script when even that is too many
              round trips.""").indent(2));
        } finally { s.stop(); }
    }

    // ------------------------------------------------------------- helpers

    static void concurrently(int n, java.util.function.IntConsumer body) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int id = i;
            Thread t = Thread.ofVirtual().unstarted(() -> { try { go.await(); body.accept(id); } catch (Exception ignored) {} });
            ts.add(t); t.start();
        }
        go.countDown();
        for (Thread t : ts) t.join();
    }

    static long info(Cli c, String field) throws IOException {
        String txt = (String) c.cmd("INFO");
        for (String line : txt.split("\n")) if (line.startsWith(field + ":")) return Long.parseLong(line.substring(field.length() + 1).trim());
        return 0;
    }

    static long pct(List<Long> xs, int p) {
        if (xs.isEmpty()) return 0;
        List<Long> s = new ArrayList<>(xs); Collections.sort(s);
        return s.get(Math.min(s.size() - 1, (int) (s.size() * p / 100.0)));
    }
}
