import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Measured experiments. Run: java src/Experiments.java [name]
 * With no name, lists them.
 */
public class Experiments {

    static final int ROUNDS = 600, CLIENTS = 4, OPS = 5;

    static Sim rig(Sim.Mode m, long seed) {
        Sim s = new Sim(m, 3, 2, 2, seed);
        s.jitterProb = 0.15; s.writers = 1;
        return s;
    }

    public static void main(String[] a) {
        String which = a.length > 0 ? a[0] : "";
        switch (which) {
            case "stores"      -> stores();
            case "jitter"      -> jitter();
            case "writeback"   -> writeback();
            case "realtime"    -> realtime();
            case "cost"        -> cost();
            case "cache"       -> cache();
            case "clock"       -> clock();
            case "lostupdate"  -> lostUpdate();
            case "checkercost" -> checkerCost();
            case "locality"    -> locality();
            case "all"         -> { stores(); jitter(); writeback(); realtime(); cost(); cache(); clock(); lostUpdate(); checkerCost(); locality(); }
            default -> System.out.println("""

                Experiments — java src/Experiments.java <name>

                  stores       violation rate of seven replication designs
                  jitter       why a naive quorum read looks perfect until the network hiccups
                  writeback    the single line of protocol that fixes it, isolated
                  realtime     how many histories are 'sensible' but still not linearizable
                  cost         what linearizability costs in round trips and latency
                  cache        cache TTL vs violation rate
                  clock        clock skew vs violation rate
                  lostupdate   a genuinely linearizable register, losing updates anyway
                  checkercost  why checking is expensive and designing is not
                  locality     linearizability composes; sequential consistency does not
                  all          every experiment
                """);
        }
    }

    static void head(String t) { System.out.println("\n" + t + "\n" + "=".repeat(t.length()) + "\n"); }

    // ------------------------------------------------------------------ 1
    static void stores() {
        head("1. Seven designs, judged by the checker  (N=3, R=W=2, 1 writer, 3 readers, 15% message hiccups)");
        System.out.printf("  %-16s %-13s %-11s %s%n", "design", "reads from", "violations", "of " + ROUNDS + " histories");
        for (Sim.Mode m : Sim.Mode.values()) {
            int bad = 0;
            for (int i = 0; i < ROUNDS; i++) if (!Checker.check(rig(m, i).run(CLIENTS, OPS, i)).ok) bad++;
            System.out.printf("  %-16s %-13s %-11d %.1f%%%n", m, readsFrom(m), bad, 100.0 * bad / ROUNDS);
        }
        System.out.println("""

              SINGLE and LEADER are linearizable because every operation passes through
              one machine that does them one at a time. QUORUM_ABD gets there without a
              single point of authority — it pays a second round trip on every read.""");
    }

    static String readsFrom(Sim.Mode m) {
        return switch (m) {
            case SINGLE -> "1 copy"; case LEADER -> "leader"; case ASYNC -> "any replica";
            case QUORUM_NAIVE -> "2 of 3"; case QUORUM_ABD -> "2 of 3 +wb";
            case QUORUM_CLOCK -> "2 of 3 +wb"; case CACHED -> "cache";
        };
    }

    // ------------------------------------------------------------------ 2
    static void jitter() {
        head("2. The same code, the same seeds — only the network changes");
        System.out.printf("  %-22s %-14s %s%n", "message hiccup rate", "no write-back", "with write-back (ABD)");
        for (double p : new double[]{0.0, 0.02, 0.05, 0.10, 0.15, 0.25, 0.40}) {
            int a = 0, b = 0;
            for (int i = 0; i < ROUNDS; i++) {
                Sim s1 = rig(Sim.Mode.QUORUM_NAIVE, i); s1.jitterProb = p;
                Sim s2 = rig(Sim.Mode.QUORUM_ABD, i);   s2.jitterProb = p;
                if (!Checker.check(s1.run(CLIENTS, OPS, i)).ok) a++;
                if (!Checker.check(s2.run(CLIENTS, OPS, i)).ok) b++;
            }
            System.out.printf("  %-22s %-14s %s%n", String.format("%.0f%%", p * 100),
                    String.format("%d (%.1f%%)", a, 100.0 * a / ROUNDS), String.format("%d (%.1f%%)", b, 100.0 * b / ROUNDS));
        }
        System.out.println("""

              A calm network produces zero violations from a design that has no
              guarantee at all. This is the shape of every consistency bug that
              reaches production: correct in staging, wrong during an incident.""");
    }

    // ------------------------------------------------------------------ 3
    static void writeback() {
        head("3. The fix, isolated: the reader writes back what it read");
        int shown = 0;
        for (int i = 0; i < 400 && shown < 2; i++) {
            History bad = rig(Sim.Mode.QUORUM_NAIVE, i).run(CLIENTS, OPS, i);
            if (Checker.check(bad).ok) continue;
            History small = Checker.minimize(bad, Checker.Opts.linearizable());
            System.out.println("  seed " + i + " — shrunk from " + bad.size() + " operations to " + small.size() + ":");
            System.out.println(small.render());
            shown++;
        }
        System.out.println("""
              Read it left to right: a write is still in flight, an early reader
              already sees the new value, and a LATER reader — one that started
              after the first reader was answered — still sees the old one. Time
              went backwards for the register. The reader's write-back exists to
              stop exactly this: before returning a value, push it to a quorum so
              nobody after you can un-see it.""");
    }

    // ------------------------------------------------------------------ 4
    static void realtime() {
        head("4. What the real-time rule actually catches");
        System.out.printf("  %-16s %-12s %-20s %s%n", "design", "not lin.", "not even seq.cons.", "sensible-but-stale");
        for (Sim.Mode m : new Sim.Mode[]{Sim.Mode.ASYNC, Sim.Mode.QUORUM_NAIVE, Sim.Mode.CACHED, Sim.Mode.QUORUM_ABD}) {
            int notLin = 0, notSeq = 0;
            for (int i = 0; i < ROUNDS; i++) {
                History h = rig(m, i).run(CLIENTS, OPS, i);
                boolean l = Checker.check(h, Checker.Opts.linearizable()).ok;
                boolean s = Checker.check(h, Checker.Opts.sequential()).ok;
                if (!l) notLin++;
                if (!s) notSeq++;
            }
            System.out.printf("  %-16s %-12d %-20d %d%n", m, notLin, notSeq, notLin - notSeq);
        }
        System.out.println("""

              The last column is the interesting one: histories where every client
              saw a perfectly coherent story in its own order, and the system was
              still lying about time. Only the real-time rule sees those.""");
    }

    // ------------------------------------------------------------------ 5
    static void cost() {
        head("5. The bill");
        System.out.printf("  %-16s %-12s %-12s %s%n", "design", "mean read", "mean write", "round trips per read");
        for (Sim.Mode m : Sim.Mode.values()) {
            double rSum = 0, wSum = 0; int rN = 0, wN = 0;
            for (int i = 0; i < 200; i++)
                for (Op o : rig(m, i).run(CLIENTS, OPS, i).ops) {
                    long d = o.response - o.invoke;
                    if (o.kind == Op.Kind.GET) { rSum += d; rN++; } else { wSum += d; wN++; }
                }
            System.out.printf("  %-16s %-12.1f %-12.1f %s%n", m, rSum / rN, wSum / wN,
                    switch (m) { case QUORUM_ABD, QUORUM_CLOCK -> "2"; case CACHED -> "0 or 2"; default -> "1"; });
        }
        System.out.println("""

              Linearizability is not free and the price is a round trip, not a
              constant. That is why every real system offers a way to opt out of
              it per request, and why 'strongly consistent read' is a billing line
              in DynamoDB.""");
    }

    // ------------------------------------------------------------------ 6
    static void cache() {
        head("6. A cache in front of a linearizable store");
        System.out.printf("  %-14s %s%n", "cache TTL", "violations of " + ROUNDS);
        for (int ttl : new int[]{0, 10, 30, 60, 120, 400}) {
            int bad = 0;
            for (int i = 0; i < ROUNDS; i++) {
                Sim s = rig(Sim.Mode.CACHED, i); s.cacheTtl = ttl;
                if (!Checker.check(s.run(CLIENTS, OPS, i)).ok) bad++;
            }
            System.out.printf("  %-14d %d (%.1f%%)%n", ttl, bad, 100.0 * bad / ROUNDS);
        }
        System.out.println("""

              The store underneath is provably linearizable at every TTL. The
              property is not a property of the store — it is a property of what
              the client can observe, and a cache is part of that.""");
    }

    // ------------------------------------------------------------------ 7
    static void clock() {
        head("7. Timestamping writes with a wall clock");
        System.out.printf("  %-20s %s%n", "one writer's skew", "violations of " + ROUNDS);
        for (int skew : new int[]{0, 5, 20, 60, 200}) {
            int bad = 0;
            for (int i = 0; i < ROUNDS; i++) {
                Sim s = rig(Sim.Mode.QUORUM_CLOCK, i); s.clockSkew = skew; s.writers = 3;
                if (!Checker.check(s.run(CLIENTS, OPS, i)).ok) bad++;
            }
            System.out.printf("  %-20d %d (%.1f%%)%n", skew, bad, 100.0 * bad / ROUNDS);
        }
        System.out.println("""

              Same read protocol, same write-back, same quorum. The only change is
              where the write's position in the order comes from: a clock nobody
              agreed on, instead of a number a quorum handed out.""");
    }

    // ------------------------------------------------------------------ 8
    static void lostUpdate() {
        head("8. A register that IS linearizable, losing updates anyway");
        System.out.println("  AtomicInteger is a linearizable register: every get and every set");
        System.out.println("  takes effect at one instant, and the JDK guarantees it.\n");
        for (int threads : new int[]{2, 4, 8}) {
            int perThread = 20000;
            System.out.printf("  %d threads x %d increments -> expected %d%n", threads, perThread, threads * perThread);
            System.out.printf("     get-then-set : %d%n", race(threads, perThread, false));
            System.out.printf("     compareAndSet: %d%n", race(threads, perThread, true));
        }
        System.out.println("""

              Linearizability makes each operation atomic. It says nothing about a
              SEQUENCE of operations — read-then-write is two operations and anyone
              may slip between them. Making a sequence atomic needs a different
              tool: one operation that both reads and decides (compare-and-set), or
              a transaction. This is the boundary between linearizability and
              serializability, in twelve lines of Java.""");
    }

    static int race(int threads, int perThread, boolean cas) {
        AtomicInteger reg = new AtomicInteger(0);
        List<Thread> ts = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            ts.add(Thread.ofVirtual().unstarted(() -> {
                for (int i = 0; i < perThread; i++) {
                    if (cas) { int v; do { v = reg.get(); } while (!reg.compareAndSet(v, v + 1)); }
                    else     { int v = reg.get(); Thread.yield(); reg.set(v + 1); }
                }
            }));
        }
        ts.forEach(Thread::start);
        ts.forEach(t -> { try { t.join(); } catch (InterruptedException e) { throw new RuntimeException(e); } });
        return reg.get();
    }

    // ------------------------------------------------------------------ 9
    static void checkerCost() {
        head("9. Why checking is hard and designing is not");
        System.out.println("  Worst case for the checker: every operation overlaps every other one, so");
        System.out.println("  nothing pins the order and it must search. Each history below is genuinely");
        System.out.println("  linearizable — the checker has to find the needle, not just fail fast.\n");
        System.out.printf("  %-12s %-16s %-18s %s%n", "operations", "states (memo)", "states (no memo)", "time (memo)");
        Checker.Opts noMemo = Checker.Opts.linearizable(); noMemo.memo = false;
        for (int k = 2; k <= 9; k++) {
            History h = allConcurrent(k, 1234 + k);
            long t0 = System.nanoTime();
            var withMemo = Checker.check(h, Checker.Opts.linearizable());
            long dt = System.nanoTime() - t0;
            String plain;
            try { plain = String.valueOf(Checker.check(h, noMemo).nodes); }
            catch (StackOverflowError e) { plain = "gave up"; }
            System.out.printf("  %-12d %-16d %-18s %.2f ms%n", h.size(), withMemo.nodes, plain, dt / 1e6);
        }
        System.out.println("""

              Deciding linearizability of a history is NP-hard in general
              (Gibbons & Korach, 1997), and the untabled column shows what that
              feels like. It is why Jepsen splits a run into one small history
              per key before checking. Note what is NOT expensive: BEING
              linearizable. The cost lives in the verification, not in the
              property — a leader answering reads is linearizable and does no
              searching at all.""");
    }

    /**
     * k writes and k reads, all issued at time 0 and all answered at time 1000,
     * so no pair is ordered by real time. The answers come from replaying a
     * random order first, which guarantees at least one valid order exists.
     */
    static History allConcurrent(int k, long seed) {
        Random rnd = new Random(seed);
        List<Op> ops = new ArrayList<>();
        for (int i = 0; i < k; i++) ops.add(Op.put(i, i + 1, 0, 1000));
        for (int i = 0; i < k; i++) ops.add(Op.get(k + i, 0, 0, 1000));
        List<Op> order = new ArrayList<>(ops);
        Collections.shuffle(order, rnd);
        int v = 0;
        for (Op o : order) { if (o.kind == Op.Kind.PUT) v = o.arg; else o.ret = v; }
        History h = new History(); h.name = "all-concurrent k=" + k;
        h.ops.addAll(ops);
        return h;
    }

    // ----------------------------------------------------------------- 10
    static void locality() {
        head("10. Two objects at once");
        int merged = 0, bothLin = 0;
        for (int i = 0; i < 300; i++) {
            History x = rig(Sim.Mode.QUORUM_ABD, i).run(3, 4, i);
            History y = rig(Sim.Mode.QUORUM_ABD, 10000 + i).run(3, 4, i);
            for (Op o : y.ops) { o.obj = "y"; o.client += 100; }
            History both = new History(); both.name = "x+y";
            both.ops.addAll(x.ops); both.ops.addAll(y.ops);
            boolean lx = Checker.check(x).ok, ly = Checker.check(y).ok;
            if (lx && ly) { bothLin++; if (Checker.check(both).ok) merged++; }
        }
        System.out.printf("  both objects individually linearizable : %d of 300%n", bothLin);
        System.out.printf("  ...and the combined history also is    : %d of 300%n", merged);

        History two = Scenarios.twoObjects();
        System.out.println("\n  Now the same question for sequential consistency:\n");
        System.out.println(two.render());
        System.out.printf("  object x alone, sequentially consistent : %s%n", Checker.check(two.project("x"), Checker.Opts.sequential()).ok);
        System.out.printf("  object y alone, sequentially consistent : %s%n", Checker.check(two.project("y"), Checker.Opts.sequential()).ok);
        System.out.printf("  both together                           : %s%n", Checker.check(two, Checker.Opts.sequential()).ok);
        System.out.println("""

              This is locality, and it is the reason linearizability is the property
              real systems chase. Make each object linearizable independently and the
              whole system is linearizable, with no global coordination. Sequential
              consistency has no such property: two individually-fine objects can
              combine into something impossible.""");
    }
}
