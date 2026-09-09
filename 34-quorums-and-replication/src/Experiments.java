import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Measured experiments. Every number in the README comes from one of these.
 *
 *   java src/Experiments.java
 *   java src/Experiments.java overlap availability partial concurrent clockskew sloppy repair leader lag latency
 */
public class Experiments {

    public static void main(String[] args) {
        if (args.length == 0) { System.out.println("""
                experiments:
                  overlap      stale-read rate for every (R,W) at N=3 and N=5   <-- the headline
                  availability write and read success as replicas die
                  partial      how often a "failed" write is readable afterwards
                  concurrent   last-write-wins lost updates vs version-vector siblings
                  clockskew    one machine with a wrong clock, under last-write-wins
                  sloppy       sloppy quorums: availability bought with staleness
                  repair       how staleness decays: read repair vs anti-entropy
                  leader       async / semi-sync / sync: acknowledged writes lost on failover
                  lag          reading a follower and not seeing your own write
                  latency      why waiting for W of N beats waiting for all N
                """); return; }
        for (String a : args) {
            System.out.println("\n########## " + a + " ##########");
            switch (a) {
                case "overlap" -> overlap();
                case "availability" -> availability();
                case "partial" -> partial();
                case "concurrent" -> concurrent();
                case "clockskew" -> clockskew();
                case "sloppy" -> sloppy();
                case "repair" -> repair();
                case "leader" -> leader();
                case "lag" -> lag();
                case "latency" -> latency();
                default -> System.out.println("unknown: " + a);
            }
        }
    }

    /** Set.of() rejects duplicates; the coordinator is sometimes also a replica. */
    static Set<String> setOf(String... ids) { return new java.util.HashSet<>(Arrays.asList(ids)); }

    // =====================================================================

    /**
     * The claim under test: if R + W > N, a read can never miss the most
     * recent successful write. Everything else is a probability, not a rule.
     */
    static void overlap() {
        System.out.println("""
                Each round: replicas fail independently (20% chance each), one write is
                attempted, then one read from a random coordinator. A "stale read" means
                the read succeeded but returned something older than the newest write the
                client had been told succeeded. Read repair is OFF so the raw property is
                what is measured.
                """);
        for (int n : new int[]{3, 5}) {
            System.out.printf("N = %d%n", n);
            System.out.println("   R    W   R+W>N   writes ok   reads ok   STALE READS");
            for (int w = 1; w <= n; w++) for (int r = 1; r <= n; r++) {
                var res = staleRun(n, r, w, 4000, 0.20, 7L);
                System.out.printf("  %2d   %2d   %-5s   %8.1f%%   %7.1f%%   %5d  %s%n",
                        r, w, (r + w > n), 100.0 * res[0] / res[3], 100.0 * res[1] / res[3], res[2],
                        res[2] == 0 ? "" : "<-- lost the guarantee");
            }
            System.out.println();
        }
    }

    /** returns {writesOk, readsOk, staleReads, rounds} */
    static long[] staleRun(int n, int r, int w, int rounds, double failP, long seed) {
        var c = new Cluster(n);
        c.N = n; c.R = r; c.W = w; c.readRepair = false;
        var rng = new Random(seed);
        long writesOk = 0, readsOk = 0, stale = 0, committed = -1;
        // The client carries its version context forward, so write i+1 is
        // provably newer than write i. Passing an empty context every time
        // would make every write look CONCURRENT with every other -- which is
        // a real bug people hit, and it silently makes new writes no-ops.
        VersionVector ctx = VersionVector.empty();
        for (int i = 0; i < rounds; i++) {
            for (Node nd : c.nodes) nd.alive = rng.nextDouble() >= failP;
            c.now = i;
            var wr = c.put(c.nodes.get(rng.nextInt(n)).id, "cli", "k", "v" + i, ctx);
            ctx = wr.vv();
            if (wr.ok()) { writesOk++; committed = i; }
            for (Node nd : c.nodes) nd.alive = rng.nextDouble() >= failP;
            var rd = c.get(c.nodes.get(rng.nextInt(n)).id, "k");
            if (!rd.ok()) continue;
            readsOk++;
            if (committed < 0) continue;
            long seen = rd.values().stream().mapToLong(v -> Long.parseLong(v.value().substring(1))).max().orElse(-1);
            if (seen < committed) stale++;
        }
        return new long[]{writesOk, readsOk, stale, rounds};
    }

    // =====================================================================

    static void availability() {
        System.out.println("How many replicas may be down before writes / reads stop? (N=3)\n");
        System.out.println("   R    W    0 down   1 down   2 down");
        for (int w = 1; w <= 3; w++) for (int r = 1; r <= 3; r++) {
            StringBuilder sb = new StringBuilder();
            for (int down = 0; down <= 2; down++) {
                var c = new Cluster(3); c.N = 3; c.R = r; c.W = w;
                for (int i = 0; i < down; i++) c.kill("n" + (2 - i));
                boolean wok = c.put("n0", "cli", "k", "v", VersionVector.empty()).ok();
                boolean rok = c.get("n0", "k").ok();
                sb.append(String.format("   %-6s", (wok ? "W" : "-") + (rok ? "R" : "-")));
            }
            System.out.printf("  %2d   %2d %s%n", r, w, sb);
        }
        System.out.println("\n  W = write works, R = read works, - = refused.");
        System.out.println("  Read the diagonal: every step you take toward durability you pay in availability.");
    }

    // =====================================================================

    static void partial() {
        System.out.println("""
                A write needs W=3 acks. One replica is slow: it stores the value but
                replies too late. The client is told the write FAILED. Then we read.
                """);
        int readable = 0, trials = 500;
        for (int i = 0; i < trials; i++) {
            var c = new Cluster(3); c.N = 3; c.R = 2; c.W = 3; c.readRepair = false;
            c.makeSlow("n" + (i % 3), true);
            var w = c.put("n0", "cli", "k", "ghost", VersionVector.empty());
            if (w.ok()) continue;
            if (!c.get("n0", "k").values().isEmpty()) readable++;
        }
        System.out.printf("failed writes that are readable afterwards: %d / %d  (%.0f%%)%n",
                readable, trials, 100.0 * readable / trials);
        System.out.println("""
                
                There is no rollback in this design and there cannot be: the coordinator
                does not know which replicas eventually applied it. "Write failed" means
                "not enough replicas confirmed", never "nothing happened". Retrying is the
                only correct client response, which is why writes have to be idempotent.""");
    }

    // =====================================================================

    static void concurrent() {
        System.out.println("Two clients read the same state and write different values, 1000 times.\n");
        for (boolean lww : new boolean[]{true, false}) {
            var c = new Cluster(3); c.N = 3; c.R = 2; c.W = 2; c.lastWriteWins = lww;
            int lost = 0, conflictsSeen = 0;
            for (int i = 0; i < 1000; i++) {
                String k = "k" + i;
                c.now = i * 10;      c.put("n0", "ana", k, "ana-value", VersionVector.empty());
                c.now = i * 10 + 1;  c.put("n1", "ben", k, "ben-value", VersionVector.empty());
                var r = c.get("n0", k);
                if (r.values().size() > 1) conflictsSeen++;
                else lost++;                        // only one value survived: one write vanished
            }
            System.out.printf("%-18s  conflicts surfaced to the app: %4d   updates silently discarded: %4d%n",
                    lww ? "last-write-wins" : "version vectors", conflictsSeen, lost);
        }
        System.out.println("""
                
                Neither column is "correct" -- they are different products. Last-write-wins
                needs no application code and always converges, and pays for it by throwing
                one of every pair of concurrent writes away with no record. Version vectors
                never lose a write, and pay for it by handing the application a decision it
                must be written to make. A shopping cart wants the second; a "last seen at"
                timestamp is perfectly happy with the first.""");
    }

    // =====================================================================

    static void clockskew() {
        System.out.println("Node n0's clock is 60 seconds fast. Last-write-wins is on.\n");
        int discarded = 0;
        for (int i = 0; i < 1000; i++) {
            var c = new Cluster(3); c.N = 3; c.R = 2; c.W = 2; c.lastWriteWins = true;
            c.skewClock("n0", 60_000);
            c.now = 1000; c.put("n0", "ana", "k", "older", VersionVector.empty());
            c.now = 2000; c.put("n1", "ben", "k", "NEWER", VersionVector.empty());
            if (!c.get("n1", "k").values().get(0).value().equals("NEWER")) discarded++;
        }
        System.out.printf("genuinely-newer writes thrown away: %d / 1000%n", discarded);
        System.out.println("""
                
                The clock is not a detail of the implementation -- under last-write-wins it
                IS the conflict resolution algorithm. One machine with NTP broken quietly
                wins every race it enters, for as long as its clock stays ahead, and nothing
                in the database reports an error. This is the strongest argument for
                version vectors, and the reason Cassandra lets the CLIENT supply the write
                timestamp when it has a better source of ordering.""");
    }

    // =====================================================================

    static void sloppy() {
        System.out.println("""
                N=3, R=2, W=2 -- so R+W>N holds. A partition cuts the coordinator off from
                two of the three replicas that own the key. 300 keys are written.
                """);
        for (boolean handoff : new boolean[]{false, true}) {
            var c = new Cluster(6);
            c.N = 3; c.R = 2; c.W = 2; c.sloppyQuorum = true; c.hintedHandoff = handoff; c.readRepair = false;
            int ok = 0, visible = 0;
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < 300; i++) keys.add("key" + i);
            for (String k : keys) {
                var home = c.homeReplicas(k);
                c.partition(setOf("n0", home.get(0).id), setOf(home.get(1).id, home.get(2).id));
                if (c.put("n0", "cli", k, "v", VersionVector.empty()).ok()) ok++;
                c.heal();
            }
            if (handoff) c.deliverHints();
            for (String k : keys) if (!c.get(c.homeReplicas(k).get(1).id, k).values().isEmpty()) visible++;
            System.out.printf("hinted handoff %-3s :  writes accepted %3d/300   visible to a strict read afterwards %3d/300%n",
                    handoff ? "ON" : "OFF", ok, visible);
        }
        System.out.println("""
                
                This is the honest reading of a sloppy quorum: it is not a quorum. W acks
                were collected, but not from the replicas that own the key, so the read set
                and the write set need not intersect at all -- R+W>N is arithmetic about a
                set the write never touched. Availability is real; the guarantee is gone
                until hinted handoff finishes, and handoff runs on a node that may itself
                die while holding the only copy.""");
    }

    // =====================================================================

    static void repair() {
        System.out.println("""
                One replica is offline while 200 keys are written, then it returns.
                Half the keys are read afterwards ("hot"); half are never read ("cold").
                """);
        var c = new Cluster(3); c.N = 3; c.R = 2; c.W = 2;
        c.kill("n2");
        for (int i = 0; i < 200; i++) c.put("n0", "cli", "k" + i, "v", VersionVector.empty());
        c.revive("n2");
        System.out.printf("straight after it returns, n2 holds %d of 200 keys%n", c.node("n2").keyCount());
        c.R = 3;
        for (int i = 0; i < 100; i++) c.get("n0", "k" + i);      // read the hot half
        System.out.printf("after reading the hot half, n2 holds %d of 200  (read repair fixed %d)%n",
                c.node("n2").keyCount(), c.readRepairs);
        for (int i = 0; i < 200; i++) c.antiEntropy("k" + i);
        System.out.printf("after a background comparison sweep, n2 holds %d of 200%n", c.node("n2").keyCount());
        System.out.println("""
                
                Read repair only ever fixes what someone reads, so a rarely-read key can stay
                wrong indefinitely -- and it is exactly the rarely-read keys whose staleness
                you discover at the worst moment. Anti-entropy (Merkle-tree comparison in
                Cassandra and Riak) is what actually bounds it, and it is the process people
                forget to schedule until an incident teaches them.""");
    }

    // =====================================================================

    static void leader() {
        System.out.println("""
                Three columns, three different questions.
                  lost on failover  : 100 writes are acknowledged, then the leader dies
                                      before replication catches up. How many acknowledged
                                      writes no longer exist?
                  refused, 1 down   : one of the two followers is unreachable. How many of
                                      100 writes are refused?
                  refused, 2 down   : both followers unreachable.
                  ... with a floor  : the same, with min-in-sync = 2 (leader + one follower).
                """);
        System.out.println("  mode         acked   lost on failover   refused 1 down   refused 2 down   refused 2 down + floor");
        for (var mode : LeaderCluster.Mode.values()) {
            var lc = new LeaderCluster(3); lc.mode = mode; lc.acksNeeded = 1;
            for (int i = 0; i < 100; i++) lc.write("k" + i, "v" + i);
            lc.failover();
            System.out.printf("  %-11s %5d   %16d   %14d   %14d   %20d%n",
                    mode, lc.acked, lc.ackedThenLost,
                    refusedWith(mode, 1, 1), refusedWith(mode, 2, 1), refusedWith(mode, 2, 2));
        }
        System.out.println("""
                
                Async never refuses a write and loses whatever had not been copied yet:
                100 acknowledged writes, gone. That column is not a bug in the failover --
                it is the bill for saying "done" early.

                Now look at the SYNC row, because it is the trap. With two followers down it
                refuses NOTHING. "Wait for all live followers" quietly becomes "wait for
                nobody" exactly when there is nobody left to wait for, so the setting chosen
                for safety degrades to the least safe one at the precise moment safety
                matters -- and reports success throughout. The last column is the fix: a
                floor on how many replicas must be in sync before a write is allowed at all
                (Kafka spells it `min.insync.replicas`, and it is why `acks=all` alone is not
                a durability setting). Semi-sync with a floor is what most people should run.""");
    }

    static int refusedWith(LeaderCluster.Mode mode, int downFollowers, int floor) {
        var lc = new LeaderCluster(3);
        lc.mode = mode; lc.acksNeeded = 1; lc.minInSync = floor;
        for (int i = 0; i < downFollowers; i++) lc.alive.put("n" + (2 - i), false);
        int refused = 0;
        for (int i = 0; i < 100; i++) if (!lc.write("k" + i, "v")) refused++;
        return refused;
    }

    // =====================================================================

    static void lag() {
        System.out.println("""
                Replication lag is a QUEUE, not a constant. The leader accepts writes at one
                rate; a follower applies them at another. 300 rounds; each round the leader
                takes `in` writes and the follower manages to apply `out`. After each round a
                user re-reads their own most recent change from a follower.
                """);
        System.out.println("  writes in   applied out   final backlog   user cannot see own change");
        for (int[] rate : new int[][]{{1, 3}, {1, 1}, {2, 1}, {5, 1}}) {
            var lc = new LeaderCluster(3); lc.mode = LeaderCluster.Mode.ASYNC;
            int missing = 0, seq = 0;
            for (int i = 0; i < 300; i++) {
                for (int k = 0; k < rate[0]; k++) lc.write("p" + (seq++), "v");
                lc.tick(rate[1]);
                if (!"v".equals(lc.readFrom("n1", "p" + (seq - 1)))) missing++;
            }
            System.out.printf("  %9d   %11d   %13d   %10d / 300%n",
                    rate[0], rate[1], lc.pending.get("n1").size(), missing);
        }
        System.out.println("""
                
                Nothing is broken here -- every one of those reads is a correct read of a
                correct replica. It is still a bug report, because the user's mental model is
                one database. The fixes are all routing rules, not storage changes: send a
                user's reads to the leader for a few seconds after they write, or pin them to
                one replica, or have the client carry the version it last saw and require a
                replica to be at least that fresh.""");
    }

    // =====================================================================

    static void latency() {
        System.out.println("""
                Why quorums are also a LATENCY device, not only a durability one.
                Each replica answers in 5 ms normally, but one request in ten hits a 200 ms
                stall. 100,000 requests to N=3 replicas; we wait for the W-th fastest reply.
                """);
        var rng = new Random(11);
        for (int w = 1; w <= 3; w++) {
            long[] samples = new long[100_000];
            for (int i = 0; i < samples.length; i++) {
                long[] t = new long[3];
                for (int j = 0; j < 3; j++) t[j] = rng.nextDouble() < 0.10 ? 200 : 5;
                Arrays.sort(t);
                samples[i] = t[w - 1];
            }
            Arrays.sort(samples);
            long slow = Arrays.stream(samples).filter(v -> v > 5).count();
            System.out.printf("wait for %d of 3:  p50 = %3d ms   p99 = %3d ms   p99.9 = %3d ms   requests that hit a stall: %5.2f%%%n",
                    w, samples[samples.length / 2], samples[(int) (samples.length * 0.99)],
                    samples[(int) (samples.length * 0.999)], 100.0 * slow / samples.length);
        }
        System.out.println("""
                
                Waiting for all three means every slow replica is YOUR slow request: the tail
                of the slowest of three. Waiting for two lets you ignore whichever replica is
                garbage-collecting right now. This is the same trick as hedged requests, and
                it is why "quorum" is often a performance decision dressed as a safety one.""");
    }
}
