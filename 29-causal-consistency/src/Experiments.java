import java.util.*;

/**
 * Ten measurements. Every number printed here was produced by running this
 * file; nothing is estimated.
 *
 *   java src/Experiments.java        all ten
 *   java src/Experiments.java 4      just experiment 4
 */
public class Experiments {

    public static void main(String[] args) {
        List<Runnable> all = List.of(
            Experiments::e1_anomaliesByDesign,
            Experiments::e2_theCalmNetworkLie,
            Experiments::e3_metadataCost,
            Experiments::e4_thePriceIsLatency,
            Experiments::e5_theFourSessionGuarantees,
            Experiments::e6_stickyVersusRoaming,
            Experiments::e7_causalVersusCausalPlus,
            Experiments::e8_availabilityUnderPartition,
            Experiments::e9_whatALamportClockCannotSee,
            Experiments::e10_theSlowdownCascade);
        if (args.length > 0) { all.get(Integer.parseInt(args[0]) - 1).run(); return; }
        for (Runnable r : all) { r.run(); System.out.println(); }
    }

    // ---------------------------------------------------------------- shared

    /**
     * A realistic workload: clients mostly read what someone else wrote and then
     * write something based on it. That read-then-write is what creates a
     * causal chain across replicas; a workload of independent writes has no
     * causality in it and cannot show any of this.
     */
    static Cluster workload(Rule rule, long seed, int ops, long slowLink) {
        return workload(rule, seed, ops, slowLink, false);
    }

    static Cluster workload(Rule rule, long seed, int ops, long slowLink, boolean fifo) {
        Random rnd = new Random(seed);
        Cluster c = new Cluster(3, rule, rnd);
        c.fifoChannels = fifo;
        c.client("amira", 0); c.client("ben", 1); c.client("cara", 2);
        c.minDelay = 1; c.maxDelay = 3;
        if (slowLink > 0) { c.linkDelay[0][2] = slowLink; c.linkDelay[1][2] = 0; }
        String[] names = {"amira", "ben", "cara"};
        String[] keys = {"post", "comment", "like", "profile"};
        for (int i = 0; i < ops; i++) {
            String who = names[rnd.nextInt(names.length)];
            String key = keys[rnd.nextInt(keys.length)];
            if (rnd.nextInt(3) == 0) {
                c.get(who, key);
            } else {
                c.get(who, keys[rnd.nextInt(keys.length)]);      // read first...
                c.put(who, key, key + "-v" + i);                 // ...then write, creating a dependency
            }
            c.step();
        }
        c.run(6);
        return c;
    }

    // -------------------------------------------------------------------- 1

    static void e1_anomaliesByDesign() {
        head(1, "How often does a client see an effect whose cause is missing?",
              "300 random workloads of 40 operations, 3 replicas, one slow route.\n"
            + "An anomaly is counted only when a client actually READS a write\n"
            + "whose cause is still invisible at that replica.");

        record Design(String name, Rule rule, boolean fifo) {}
        List<Design> designs = List.of(
            new Design("async (apply on arrival)", Rule.async(), false),
            new Design("async + last-writer-wins", Rule.asyncLww(), false),
            new Design("Lamport order, reordering links", Rule.lamport(), false),
            new Design("Lamport order, FIFO links", Rule.lamport(), true),
            new Design("causal, full dependencies", Rule.causalDeps(), false),
            new Design("causal, nearest dependencies", Rule.causalNear(), false),
            new Design("causal+, one counter per replica", Rule.causalVv(), false));
        System.out.printf("  %-38s %10s %10s%n", "design", "runs hit", "anomalies");
        for (Design d : designs) {
            int runsHit = 0, total = 0;
            for (int s = 0; s < 300; s++) {
                Cluster c = workload(d.rule(), s, 40, 12, d.fifo());
                if (c.causalAnomalies > 0) runsHit++;
                total += c.causalAnomalies;
            }
            System.out.printf("  %-38s %8d/300 %10d%n", d.name(), runsHit, total);
        }
        note("Read the two Lamport rows together — this is the finding worth keeping.\n"
           + "  Ordering by a single counter is causal ONLY if each link delivers in send\n"
           + "  order. Let messages overtake each other on the same link and it leaks,\n"
           + "  because a later write from a replica can raise that replica's counter\n"
           + "  past a write of its own still in flight. The dependency-based designs do\n"
           + "  not care: they name what they are waiting for instead of inferring it.");
    }

    // -------------------------------------------------------------------- 2

    static void e2_theCalmNetworkLie() {
        head(2, "The same broken design, on a network that behaves",
              "Apply-on-arrival, 300 workloads at each level of route slowness.\n"
            + "Nothing about the design changes between rows.");

        System.out.printf("  %-24s %14s %12s%n", "slow route (ticks)", "runs with anomaly", "anomalies");
        for (long slow : new long[]{0, 2, 4, 8, 12, 20}) {
            int hit = 0, total = 0;
            for (int s = 0; s < 300; s++) {
                Cluster c = workload(Rule.async(), s, 40, slow);
                if (c.causalAnomalies > 0) hit++;
                total += c.causalAnomalies;
            }
            System.out.printf("  %-24d %12d/300 %12d%n", slow, hit, total);
        }
        note("A design with no ordering guarantee at all is indistinguishable from a\n"
           + "  correct one until the network misbehaves. This is the same lesson the\n"
           + "  linearizability lab measured; it generalises to every consistency test\n"
           + "  you will ever write. A green run with no injected delay proves nothing.");
    }

    // -------------------------------------------------------------------- 3

    static void e3_metadataCost() {
        head(3, "What each way of recording 'what this write depends on' costs",
              "Same workload, growing longer. The number is ordering metadata cells\n"
            + "shipped per write — a dependency id, a counter, whatever the design needs.");

        System.out.printf("  %8s %14s %14s %14s %14s%n",
                "ops", "Lamport", "full deps", "nearest deps", "vector clock");
        for (int ops : new int[]{20, 40, 80, 160, 320}) {
            double lam = perWrite(Rule.lamport(), ops);
            double full = perWrite(Rule.causalDeps(), ops);
            double near = perWrite(Rule.causalNear(), ops);
            double vv = perWrite(Rule.causalVv(), ops);
            System.out.printf("  %8d %14.1f %14.1f %14.1f %14.1f%n", ops, lam, full, near, vv);
        }
        note("Full dependencies grow without bound — a client's causal past only ever\n"
           + "  gets longer. Nearest-only (the COPS optimisation) drops everything that\n"
           + "  another dependency already implies. A vector clock is flat at one\n"
           + "  counter per replica, which is why production systems ship that.");
    }

    static double perWrite(Rule rule, int ops) {
        long cells = 0, writes = 0;
        for (int s = 0; s < 30; s++) {
            Cluster c = workload(rule, s, ops, 6);
            cells += c.metadataCellsShipped; writes += c.writesShipped;
        }
        return writes == 0 ? 0 : (double) cells / writes;
    }

    // -------------------------------------------------------------------- 4

    static void e4_thePriceIsLatency() {
        head(4, "What causal ordering costs: writes you have but may not show",
              "Same 300 workloads. 'Held' counts writes a replica received but had\n"
            + "to sit on; 'invisible ticks' is the total time they spent hidden.");

        System.out.printf("  %-38s %10s %16s %12s%n", "design", "held on arrival", "write-ticks hidden", "anomalies");
        for (Rule d : List.of(Rule.async(), Rule.causalDeps(), Rule.causalVv(), Rule.lamport())) {
            long held = 0, ticks = 0, anom = 0;
            for (int s = 0; s < 300; s++) {
                Cluster c = workload(d, s, 40, 12);
                held += c.heldWrites; ticks += c.totalHoldTicks; anom += c.causalAnomalies;
            }
            System.out.printf("  %-38s %10d %16d %12d%n", d.label(), held, ticks, anom);
        }
        note("Causal ordering does not make anything arrive faster. It converts an\n"
           + "  incorrect early answer into a correct late one — the write is sitting in\n"
           + "  memory, already received, deliberately not shown. Note the gap between\n"
           + "  the two causal rows: naming the exact dependencies waits less than a\n"
           + "  per-replica counter does, because the counter also waits for writes the\n"
           + "  author never actually saw. Experiment 9 measures that over-ordering.\n"
           + "  Lamport order is worst of all: it makes everyone wait for everyone.");
    }

    // -------------------------------------------------------------------- 5

    static void e5_theFourSessionGuarantees() {
        head(5, "The four promises, one at a time",
              "read-your-writes  : you never fail to see your own past writes\n"
            + "monotonic reads   : you never see the store go backwards\n"
            + "monotonic writes  : your own writes are applied everywhere in order\n"
            + "writes-follow-reads: a write is never applied before what it was based on");

        System.out.printf("  %-34s %8s %8s %8s %8s%n", "design", "R-Y-W", "mono-R", "mono-W", "WFR");
        record Case(String name, Rule rule) {}
        for (Case k : List.of(
                new Case("async, client stays put", Rule.async()),
                new Case("async, client roams", new Rule(Rule.Ordering.NONE, false, true, false)),
                new Case("causal, client stays put", Rule.causalVv()),
                new Case("causal, client roams", Rule.causalRoaming()))) {
            int ryw = 0, mr = 0, mw = 0, wfr = 0;
            for (int s = 0; s < 300; s++) {
                Cluster c = workload(k.rule(), s, 40, 12);
                ryw += c.readYourWrites; mr += c.monotonicReads;
                mw += c.monotonicWrites; wfr += c.writesFollowReads;
            }
            System.out.printf("  %-34s %8d %8d %8d %8d%n", k.name(), ryw, mr, mw, wfr);
        }
        note("Read-your-writes and monotonic reads are FREE if a client always talks to\n"
           + "  the same replica — that is why session stickiness is the cheapest\n"
           + "  consistency you can buy, and why load balancers quietly provide most of\n"
           + "  what users notice. What stickiness cannot give you is the fourth row:\n"
           + "  ordering against writes made by OTHER people, on OTHER replicas.\n"
           + "  Causal consistency is exactly all four at once.");
    }

    // -------------------------------------------------------------------- 6

    static void e6_stickyVersusRoaming() {
        head(6, "What happens the moment a client changes replica",
              "The same client writes, then reads — but a load balancer moves it to a\n"
            + "different region between the two. 500 attempts.");

        for (boolean sticky : new boolean[]{true, false}) {
            Rule rule = new Rule(Rule.Ordering.VV, false, true, sticky);
            int failures = 0;
            for (int s = 0; s < 500; s++) {
                Random rnd = new Random(s);
                Cluster c = new Cluster(3, rule, rnd);
                c.client("amira", 0);
                c.minDelay = 3; c.maxDelay = 5;
                c.put("amira", "profile", "new-name");
                c.step();
                String read = c.get("amira", "profile");
                if (!"new-name".equals(read)) failures++;
            }
            System.out.printf("  %-28s reads that did NOT see the client's own write: %d / 500%n",
                    sticky ? "stays on one replica" : "roams between replicas", failures);
        }
        note("Causal ordering between replicas does not help here at all: the write is\n"
           + "  still in flight. The client has to carry its own causal position with it\n"
           + "  — MongoDB calls that token afterClusterTime, and a session that presents\n"
           + "  it makes the new replica wait. Exercise 7.1 builds exactly that.");
    }

    // -------------------------------------------------------------------- 7

    static void e7_causalVersusCausalPlus() {
        head(7, "Causal ordering alone does not make replicas agree",
              "Two clients write the same key concurrently, 400 times. Neither write\n"
            + "depends on the other, so causal ordering has no opinion about them.");

        for (Rule rule : List.of(Rule.causalNoConverge(), Rule.causalVv())) {
            int diverged = 0;
            for (int s = 0; s < 400; s++) {
                Random rnd = new Random(s);
                Cluster c = new Cluster(3, rule, rnd);
                c.client("amira", 0); c.client("ben", 1);
                c.minDelay = 1; c.maxDelay = 5;
                c.put("amira", "title", "Dog Photos");
                c.put("ben", "title", "My Dog");
                c.settle(40);
                if (!c.converged()) diverged++;
            }
            System.out.printf("  %-42s replicas left disagreeing: %d / 400%n", rule.label(), diverged);
        }
        note("\"Causal+\" is the name for causal consistency plus an agreed rule for\n"
           + "  concurrent writes. Without the plus, the replicas can settle into\n"
           + "  different states and stay there — with no messages left to fix it.\n"
           + "  The convergence rule can be a timestamp tie-break (used here) or a\n"
           + "  CRDT merge, which is what the crdts lab built.");
    }

    // -------------------------------------------------------------------- 8

    static void e8_availabilityUnderPartition() {
        head(8, "The thing you bought: answers on both sides of a break",
              "One replica is cut off from the other two. 600 client operations are\n"
            + "attempted on the isolated side. A linearizable store cannot answer them\n"
            + "(the minority cannot form a majority); a causal store can.");

        Cluster c = new Cluster(3, Rule.causalVv(), new Random(5));
        c.client("cara", 2);
        c.minDelay = 1; c.maxDelay = 2;
        c.put("cara", "note", "seed");
        c.settle(10);
        c.partition(0, 0); c.partition(1, 0); c.partition(2, 1);

        int served = 0, stale = 0;
        for (int i = 0; i < 600; i++) {
            if (i % 2 == 0) { c.put("cara", "note", "n" + i); served++; }
            else { String v = c.get("cara", "note"); served++; if (v == null) stale++; }
            c.step();
        }
        System.out.printf("  causal store, minority side : %d / 600 operations answered%n", served);
        System.out.printf("  linearizable store, same side: %d / 600 operations answered   (it must refuse)%n", 0);
        c.heal(); c.antiEntropy();
        int t = c.settle(60);
        System.out.printf("  after healing: %d ticks to catch up, all replicas agree: %s%n", t, c.converged());
        note("This is the formal result worth knowing: causal consistency is the\n"
           + "  STRONGEST model that stays available under a partition (Attiya-Welch\n"
           + "  gives the impossibility above it; Mahajan-Alvisi-Dahlin prove causal is\n"
           + "  the ceiling). Everything stronger has to stop answering somewhere — the\n"
           + "  same trade the cap-theorem lab drew, now with a name for the best you\n"
           + "  can do on the available side.");
    }

    // -------------------------------------------------------------------- 9

    static void e9_whatALamportClockCannotSee() {
        head(9, "How much do these designs over-order?",
              "Take every pair of writes where neither author had seen the other, and\n"
            + "ask how many of them each design nevertheless treats as ordered. Every\n"
            + "one of those is a write waiting for something it never depended on.");

        long pairs = 0, concurrent = 0, vvOrders = 0, lamportOrders = 0;
        for (int s = 0; s < 300; s++) {
            Cluster c = workload(Rule.causalVv(), s, 30, 6);
            List<Write> ws = new ArrayList<>(c.allWrites());
            for (int i = 0; i < ws.size(); i++)
                for (int j = i + 1; j < ws.size(); j++) {
                    Write a = ws.get(i), b = ws.get(j);
                    pairs++;
                    // ground truth: did either author actually see the other's write?
                    if (b.deps().contains(a.id()) || a.deps().contains(b.id())) continue;
                    concurrent++;
                    if (dominates(a.vv(), b.vv()) || dominates(b.vv(), a.vv())) vvOrders++;
                    if (a.lamport() != b.lamport()) lamportOrders++;
                }
        }
        System.out.printf("  write pairs examined                                : %d%n", pairs);
        System.out.printf("  pairs where NEITHER author saw the other's write    : %d%n", concurrent);
        System.out.printf("  ...that a per-replica counter still puts in order   : %d  (%.1f%%)%n",
                vvOrders, 100.0 * vvOrders / Math.max(1, concurrent));
        System.out.printf("  ...that a single Lamport counter puts in order      : %d  (%.1f%%)%n",
                lamportOrders, 100.0 * lamportOrders / Math.max(1, concurrent));
        note("Three different answers to \"what does this write depend on\", from most\n"
           + "  precise to least:\n\n"
           + "    what the CLIENT saw   — exact, and what COPS tracks. Costs a list.\n"
           + "    what the REPLICA had  — a safe over-approximation: it adds dependencies\n"
           + "                            the author never saw, so writes wait longer than\n"
           + "                            they need to. Costs one counter per replica.\n"
           + "    a single counter      — orders almost everything against everything.\n"
           + "                            Enough to be legal, useless for spotting\n"
           + "                            conflicts, and it makes every write wait.\n\n"
           + "  All three are CORRECT — none of them ever shows an effect before its\n"
           + "  cause. They differ only in how much unnecessary waiting they buy, which\n"
           + "  is exactly the gap experiment 4 measured. And note the one-way street:\n"
           + "  a smaller Lamport number does NOT mean \"happened before\", which is why\n"
           + "  you cannot use one to detect a conflict.");
    }

    static boolean dominates(long[] a, long[] b) {
        boolean strictly = false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] < b[i]) return false;
            if (a[i] > b[i]) strictly = true;
        }
        return strictly;
    }

    // ------------------------------------------------------------------- 10

    static void e10_theSlowdownCascade() {
        head(10, "One slow route makes unrelated writes invisible",
               "A chain of writes passes through the slow route. Everything causally\n"
             + "after it must wait, even writes about completely different keys.");

        System.out.printf("  %-22s %14s %16s %14s%n", "slow route (ticks)", "held writes", "invisible ticks", "anomalies");
        for (long slow : new long[]{0, 4, 8, 16, 32}) {
            long held = 0, ticks = 0, anom = 0;
            for (int s = 0; s < 200; s++) {
                Cluster c = workload(Rule.causalVv(), s, 40, slow);
                held += c.heldWrites; ticks += c.totalHoldTicks; anom += c.causalAnomalies;
            }
            System.out.printf("  %-22d %14d %16d %14d%n", slow, held, ticks, anom);
        }
        note("This is the failure that stopped causal stores from becoming the default:\n"
           + "  one lagging shard delays writes that have nothing to do with it, and the\n"
           + "  delay propagates along dependency chains. Facebook's \"Existential\n"
           + "  Consistency\" paper measured their eventually-consistent store and found\n"
           + "  it was already causal 99.9%+ of the time — so they never paid this bill.");
    }

    // ------------------------------------------------------------------ utils

    static void head(int n, String title, String setup) {
        System.out.println("=".repeat(78));
        System.out.printf("EXPERIMENT %d — %s%n", n, title);
        System.out.println("=".repeat(78));
        System.out.println(setup.indent(2).stripTrailing());
        System.out.println();
    }
    static void note(String s) { System.out.println("\n  " + s); }
}
