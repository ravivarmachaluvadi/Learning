import java.util.*;
import java.util.function.Supplier;

/**
 * Nine experiments. Each turns a claim in the README into something you can watch.
 *
 *     java src/Experiments.java        run all
 *     java src/Experiments.java 1 5    run 1 and 5
 */
public final class Experiments {

    static final List<String> KEYS = Tests.keys(200_000);

    public static void main(String[] args) {
        Set<Integer> only = new HashSet<>();
        for (String a : args) only.add(Integer.parseInt(a));
        List<Object[]> all = List.of(
            new Object[]{1, "How much moves when the cluster changes size?", (Runnable) Experiments::e1},
            new Object[]{2, "Why one point per machine is not enough",       (Runnable) Experiments::e2},
            new Object[]{3, "Where does a dead machine's load actually go?", (Runnable) Experiments::e3},
            new Object[]{4, "Monotonicity: movement that had no reason to happen", (Runnable) Experiments::e4},
            new Object[]{5, "The miss storm: what your database feels",      (Runnable) Experiments::e5},
            new Object[]{6, "Four strategies, same workload",                (Runnable) Experiments::e6},
            new Object[]{7, "Bounded loads: capping the tail",               (Runnable) Experiments::e7},
            new Object[]{8, "Machines that are not the same size",           (Runnable) Experiments::e8},
            new Object[]{9, "Two clients that disagree about who is alive",  (Runnable) Experiments::e9});
        for (Object[] r : all) {
            if (!only.isEmpty() && !only.contains((Integer) r[0])) continue;
            System.out.println("\n" + "=".repeat(76));
            System.out.printf("EXPERIMENT %d -- %s%n", r[0], r[1]);
            System.out.println("=".repeat(76));
            ((Runnable) r[2]).run();
        }
        System.out.println();
    }

    // ==================================================================== 1

    /**
     * PREDICT FIRST: a 10-machine cache. You add one more, taking it to 11. What
     * fraction of the keys are now on a different machine than before?
     */
    static void e1() {
        System.out.println("  200,000 keys. Each row grows the cluster by one machine.\n");
        System.out.printf("  %-14s %14s %14s %14s%n", "change", "modulo", "ring(vnodes=200)", "rendezvous");
        for (int n : new int[]{2, 4, 10, 50, 100}) {
            double[] r = new double[3];
            Placement[] ps = {new Placements.Modulo(), new Ring(200), new Placements.Rendezvous()};
            for (int i = 0; i < 3; i++) {
                Placement p = ps[i];
                for (int k = 0; k < n; k++) p.addNode("n" + k);
                Map<String, String> before = Tests.assign(p, KEYS);
                p.addNode("n" + n);
                r[i] = Tests.movedFraction(before, Tests.assign(p, KEYS));
            }
            System.out.printf("  %-14s %13.1f%% %13.1f%% %13.1f%%   (ideal %.1f%%)%n",
                    n + " -> " + (n + 1), r[0] * 100, r[1] * 100, r[2] * 100, 100.0 / (n + 1));
        }
        note("""
            The "ideal" column is the theoretical floor: to have n+1 machines sharing
            the keys evenly, at least 1/(n+1) of them MUST end up somewhere new. That
            movement is unavoidable and it is not the problem.

            Everything above the floor is waste, and modulo is almost entirely waste.
            Look at the 100 -> 101 row: the ring moves roughly the 1% it has to, and
            modulo moves ~99% of the dataset to accomplish exactly the same
            rebalancing. It is not a little worse. It is worse by a factor of the
            cluster size, and it gets worse as you grow.

            The cause is one character: the `% n` in modulo's formula. Because the
            machine COUNT is in the expression, every key's answer is a function of a
            global property of the cluster, and changing that property changes every
            answer at once. The ring's rule -- "the first machine clockwise" -- never
            mentions how many machines there are.""");
    }

    // ==================================================================== 2

    /**
     * PREDICT FIRST: eight machines, one position each on the circle. Is the load
     * even? (Eight random points on a circle -- do they cut it into eight equal arcs?)
     */
    static void e2() {
        System.out.println("  8 machines, 200,000 keys. Only the number of ring positions changes.\n");
        System.out.printf("  %-10s %12s %12s %14s%n", "positions", "busiest", "quietest", "imbalance");
        for (int v : new int[]{1, 2, 5, 20, 50, 100, 200, 500, 1000}) {
            Ring r = new Ring(v);
            for (int i = 0; i < 8; i++) r.addNode("n" + i);
            Map<String, Integer> c = Tests.counts(r, KEYS);
            int max = Collections.max(c.values()), min = Collections.min(c.values());
            System.out.printf("  %-10d %,12d %,12d %13.2fx%s%n", v, max, min, Tests.imbalance(c),
                    v == 1 ? "   <- one point each" : v == 100 ? "   <- typical production setting" : "");
        }
        note("""
            A fair share is 25,000 keys. With one position each, the busiest machine
            holds far more than that -- because eight random points do NOT cut a circle
            into eight equal arcs. Some arcs come out huge and some almost vanish, and
            the arc IS the machine's share.

            Virtual nodes fix it by averaging: scatter each machine across many
            unrelated positions and its total share is the sum of many independent
            arcs, whose relative variation shrinks roughly as 1/sqrt(positions). That
            is the entire justification, and it is why the number is in the hundreds
            rather than the tens.

            The cost is memory and rebuild time: the ring holds machines x positions
            entries, so 1,000 machines at 200 positions is a 200,000-entry sorted array
            that every client keeps and re-sorts on every membership change. This is
            exactly why Cassandra lowered its default from 256 to 16 and added an
            allocation algorithm to keep the balance without the count.""");
    }

    // ==================================================================== 3

    /**
     * PREDICT FIRST: eight machines, one dies. Its keys have to go somewhere. Do they
     * spread across the seven survivors, or land on one of them?
     */
    static void e3() {
        for (int v : new int[]{1, 200}) {
            Ring r = new Ring(v);
            for (int i = 0; i < 8; i++) r.addNode("n" + i);
            Map<String, String> before = Tests.assign(r, KEYS);
            Map<String, Integer> loadBefore = Tests.counts(r, KEYS);
            r.removeNode("n3");
            Map<String, String> after = Tests.assign(r, KEYS);
            Map<String, Integer> loadAfter = Tests.counts(r, KEYS);

            System.out.printf("%n  --- %d position%s per machine ---%n", v, v == 1 ? "" : "s");
            System.out.printf("  n3 held %,d keys. After it died they went to:%n", loadBefore.get("n3"));
            Map<String, Integer> inherited = new TreeMap<>();
            for (String k : KEYS) if (before.get(k).equals("n3")) inherited.merge(after.get(k), 1, Integer::sum);
            for (Map.Entry<String, Integer> e : inherited.entrySet()) {
                int was = loadBefore.get(e.getKey()), now = loadAfter.get(e.getKey());
                System.out.printf("    %-4s +%,7d   (%,d -> %,d, %+.0f%%)%n",
                        e.getKey(), e.getValue(), was, now, 100.0 * (now - was) / was);
            }
        }
        note("""
            With one position each, every one of n3's keys lands on the SINGLE machine
            that happened to sit next to it on the circle. Read the numbers above: n7
            went from 1,657 keys to 9,653 -- it was the QUIETEST machine in the cluster
            and is now by far the busiest, in one step, with no warning.

            Note what that means if the cluster were near capacity. The inheritor is
            chosen by an accident of the circle, not by who has room, so it may well be
            a machine that cannot take the load -- and when it falls over, ITS whole
            share lands on ITS single neighbour. That is a cascading failure with a
            concrete mechanism, and it is a real way clusters die.

            With hundreds of positions each, n3's share is cut into hundreds of small
            arcs whose neighbours are all different machines, so the load spreads over
            every survivor and each takes about a seventh.

            So virtual nodes are not only about steady-state balance. They are what
            makes a machine's death a small even bump instead of a targeted attack on
            one neighbour.""");
    }

    // ==================================================================== 4

    /**
     * PREDICT FIRST: when you ADD a machine, should any key ever move from one
     * existing machine to another existing machine?
     */
    static void e4() {
        System.out.println("  10 machines -> 11. Classifying every key that moved.\n");
        System.out.printf("  %-22s %16s %16s %14s%n", "strategy", "moved to newcomer", "moved elsewhere", "total moved");
        for (Supplier<Placement> f : List.<Supplier<Placement>>of(
                Placements.Modulo::new, () -> new Ring(200), Placements.Rendezvous::new)) {
            Placement p = f.get();
            for (int i = 0; i < 10; i++) p.addNode("n" + i);
            Map<String, String> before = Tests.assign(p, KEYS);
            p.addNode("NEW");
            Map<String, String> after = Tests.assign(p, KEYS);
            int toNew = 0, elsewhere = 0;
            for (String k : KEYS) {
                if (before.get(k).equals(after.get(k))) continue;
                if (after.get(k).equals("NEW")) toNew++; else elsewhere++;
            }
            System.out.printf("  %-22s %,16d %,16d %,14d%n", p.name(), toNew, elsewhere, toNew + elsewhere);
        }
        note("""
            The middle column is the one to read. A key moving to the new machine is
            work that had to happen. A key moving from one old machine to another old
            machine is pure waste -- nothing about either of them changed.

            The ring and rendezvous both put a hard ZERO in that column. That property
            has a name, MONOTONICITY, and it is the formal statement of what consistent
            hashing guarantees: adding a machine only ever pulls keys onto the
            newcomer, never sideways.

            Modulo has no such property, and the middle column is where nearly all of
            its movement lives.""");
    }

    // ==================================================================== 5

    /**
     * PREDICT FIRST: a 10-machine cache serving 90% hit ratio. You add an 11th machine
     * mid-flight. What does your database see in the seconds afterwards?
     */
    static void e5() {
        System.out.println("  A cache of 10 machines, 300,000 requests, skewed traffic (80% on the hot 20%).");
        System.out.println("  At request 150,000 an 11th machine is added. Hit ratio per 25,000 requests:\n");
        int keyspace = 40_000, requests = 300_000, window = 25_000, eventAt = 150_000;

        for (Supplier<Placement> f : List.<Supplier<Placement>>of(Placements.Modulo::new, () -> new Ring(200))) {
            Placement p = f.get();
            for (int i = 0; i < 10; i++) p.addNode("n" + i);
            Map<String, Set<String>> cache = new HashMap<>();
            for (String n : p.nodes()) cache.put(n, new HashSet<>());
            Random rnd = new Random(9);
            List<Integer> hitsPerWindow = new ArrayList<>();
            int hits = 0, originAfterEvent = 0;

            for (int i = 0; i < requests; i++) {
                if (i == eventAt) { p.addNode("n10"); cache.put("n10", new HashSet<>()); }
                int id = rnd.nextDouble() < 0.8 ? rnd.nextInt(keyspace / 5) : rnd.nextInt(keyspace);
                String key = "user:" + (100000 + id);
                String node = p.owner(key);
                boolean hit = cache.get(node).contains(key);
                if (hit) hits++; else cache.get(node).add(key);
                if (!hit && i >= eventAt && i < eventAt + window) originAfterEvent++;
                if ((i + 1) % window == 0) { hitsPerWindow.add(hits); hits = 0; }
            }
            StringBuilder bar = new StringBuilder();
            for (int w = 0; w < hitsPerWindow.size(); w++) {
                if (w == eventAt / window) bar.append(" |ADD| ");
                bar.append(String.format("%4.0f%% ", 100.0 * hitsPerWindow.get(w) / window));
            }
            System.out.printf("  %-18s %s%n", p.name(), bar);
            System.out.printf("  %-18s database hit %,d times in the 25,000 requests right after the change%n%n",
                    "", originAfterEvent);
        }
        note("""
            Read the window just after |ADD|. That single dip is the whole reason
            consistent hashing exists, and averaging it away over the following minute
            is exactly how this outage gets missed in a post-mortem.

            Every key that moved is a cache entry now sitting on the wrong machine, so
            the next request for it MISSES and goes to your database -- and they all
            move at the same instant, so they all miss at the same instant. Under
            modulo, adding one machine invalidates almost the entire cache at a stroke
            and your database briefly receives close to your full unfiltered request
            rate with nothing in front of it. Under the ring, roughly 1/11 of the cache
            goes cold and the dip is small enough to absorb.

            Notice also that both recover. The storm is a TRANSIENT, which is what
            makes it dangerous: it is over before you have finished reading the alert,
            it does not show up in a five-minute average, and the thing it took down
            was your database rather than your cache.

            Worth naming plainly: consistent hashing's product is not tidy data
            movement. It is that YOUR SOURCE OF TRUTH SURVIVES A DEPLOY.""");
    }

    // ==================================================================== 6

    /** PREDICT FIRST: which of these four is best? (It is a trick question.) */
    static void e6() {
        System.out.println("  100 machines, 200,000 keys.\n");
        System.out.printf("  %-24s %11s %16s %14s %12s%n",
                "strategy", "imbalance", "moved on remove", "lookup", "memory");
        record Row(Placement p, String mem) {}
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(new Ring(200), "100 x 200 entries"));
        rows.add(new Row(new Ring(20), "100 x 20 entries"));
        rows.add(new Row(new Placements.Rendezvous(), "the node list"));
        rows.add(new Row(new Placements.Jump(), "nothing at all"));
        rows.add(new Row(new Placements.Modulo(), "the node list"));

        for (Row row : rows) {
            Placement p = row.p();
            for (int i = 0; i < 100; i++) p.addNode("n" + i);
            Map<String, Integer> c = Tests.counts(p, KEYS);
            Map<String, String> before = Tests.assign(p, KEYS);

            String movedTxt;
            try {
                p.removeNode("n99");                            // the LAST one, so jump can try
                movedTxt = String.format("%.1f%%", Tests.movedFraction(before, Tests.assign(p, KEYS)) * 100);
                p.addNode("n99");
            } catch (UnsupportedOperationException e) { movedTxt = "n/a"; }

            long t0 = System.nanoTime();
            for (String k : KEYS) p.owner(k);
            double us = (System.nanoTime() - t0) / 1000.0 / KEYS.size();

            System.out.printf("  %-24s %10.2fx %16s %11.3f us %12s%n",
                    p.name(), Tests.imbalance(c), movedTxt, us, row.mem());
        }

        System.out.println("\n  and what happens if any machine other than the last one dies:");
        Placement j = new Placements.Jump();
        for (int i = 0; i < 5; i++) j.addNode("n" + i);
        try { j.removeNode("n2"); System.out.println("    jump hash: removed it"); }
        catch (UnsupportedOperationException e) { System.out.println("    jump hash: " + e.getMessage()); }

        note("""
            There is no winner column, which is the point.

            RING          the only one of these that both survives arbitrary machine
                          death and answers in O(log positions) regardless of cluster
                          size. Pays for it in memory and in a tuning knob you have to
                          get right.
            RENDEZVOUS    better balance than the ring, no tuning, no memory, and a
                          dead machine's keys spread perfectly. Costs a scan of every
                          machine per lookup -- fine at 10 machines, painful at 1,000.
            JUMP HASH     the best balance and literally zero memory, and it CANNOT
                          express "machine 2 died". Use it to split a dataset into n
                          parts, never for a cluster where anything can fail.
            MODULO        perfectly balanced, instant, no memory -- and it is on this
                          list to make the point that balance was never the hard part.
                          Experiment 1 is the column that disqualifies it.

            Pick by the question "what changes about my cluster, and how often?" -- not
            by the balance column, which almost all of them win.""");
    }

    // ==================================================================== 7

    /**
     * PREDICT FIRST: virtual nodes bring imbalance to a few percent. If the items are
     * live REQUESTS rather than stored keys, is a few percent good enough?
     */
    static void e7() {
        System.out.println("  10 machines, 200,000 requests. Capping how much any one may take.\n");
        System.out.printf("  %-26s %12s %14s %16s%n", "setting", "busiest", "imbalance", "sent elsewhere");
        Ring plain = new Ring(200);
        for (int i = 0; i < 10; i++) plain.addNode("n" + i);
        Map<String, Integer> pc = Tests.counts(plain, KEYS);
        System.out.printf("  %-26s %,12d %13.3fx %16s%n", "ring, no cap",
                Collections.max(pc.values()), Tests.imbalance(pc), "-");

        for (double c : new double[]{1.25, 1.05, 1.01}) {
            Placements.BoundedLoad bl = new Placements.BoundedLoad(200, c);
            for (int i = 0; i < 10; i++) bl.addNode("n" + i);
            bl.reset();
            for (String k : KEYS) bl.owner(k);
            Map<String, Integer> m = bl.loads();
            System.out.printf("  %-26s %,12d %13.3fx %,16d%n", bl.name(),
                    Collections.max(m.values()), Tests.imbalance(m), bl.displaced);
        }
        note("""
            Read the last column as the price. A capped machine turns work away, and
            that work walks clockwise to somebody with room -- so tightening the cap
            buys evenness by sending more items somewhere other than their natural
            home.

            The catch that decides where you can use this: the answer for a key now
            depends on how many OTHER items have already been placed. It is stateful.
            Two clients with different traffic will place the same key differently, and
            you cannot recompute an item's location later from the key alone.

            So this is a load balancer's tool -- HAProxy ships it -- and not a
            database's. For stored data you need the location to be a pure function of
            the key, which is precisely what the plain ring gives you.""");
    }

    // ==================================================================== 8

    /** PREDICT FIRST: one machine has 4x the RAM of the others. How do you tell the ring? */
    static void e8() {
        Ring r = new Ring(200);
        r.addNode("small-1", 1); r.addNode("small-2", 1); r.addNode("small-3", 1);
        r.addNode("large-1", 4); r.addNode("large-2", 4);
        Map<String, Integer> c = Tests.counts(r, KEYS);
        int unit = 200_000 / 11;                          // 1+1+1+4+4 = 11 shares
        System.out.println("  three small machines (weight 1) and two large ones (weight 4)\n");
        System.out.printf("  %-12s %12s %12s %10s%n", "machine", "keys", "fair share", "ratio");
        for (Map.Entry<String, Integer> e : c.entrySet()) {
            int w = e.getKey().startsWith("large") ? 4 : 1;
            System.out.printf("  %-12s %,12d %,12d %9.2fx%n", e.getKey(), e.getValue(), unit * w,
                    e.getValue() / (double) (unit * w));
        }
        note("""
            A machine's share is just the total width of its arcs, so giving it four
            times as many positions gives it four times the share. There is no separate
            weighting mechanism -- the weight IS the position count.

            This matters more than it looks, because real clusters are never uniform:
            you buy new hardware, instance types change, and one generation of machine
            has twice the memory of the last. Without weights you would have to size
            the whole cluster to its smallest member.

            The catch: weight is expressed in whole positions, so at low position
            counts you cannot express fine ratios -- weight 1 vs 1.3 needs enough
            positions for that difference to exist. Another reason production numbers
            are in the hundreds.""");
    }

    // ==================================================================== 9

    /**
     * PREDICT FIRST: you add a machine. For a few seconds, half your clients know
     * about it and half do not. How many keys do the two halves disagree about -- and
     * does it matter which strategy you chose?
     */
    static void e9() {
        System.out.println("  A machine is added. Client A has heard; client B has not yet.\n");
        System.out.printf("  %-22s %18s %18s%n", "strategy", "keys disagreed", "of 200,000");
        for (Supplier<Placement> f : List.<Supplier<Placement>>of(Placements.Modulo::new, () -> new Ring(200))) {
            Placement a = f.get(), b = f.get();
            for (int i = 0; i < 10; i++) { a.addNode("n" + i); b.addNode("n" + i); }
            a.addNode("n10");                                  // only A knows
            int disagree = 0;
            for (String k : KEYS) if (!a.owner(k).equals(b.owner(k))) disagree++;
            System.out.printf("  %-22s %,18d %17.1f%%%n", a.name(), disagree, 100.0 * disagree / KEYS.size());
        }
        note("""
            This is the failure that makes consistent hashing an OPERATIONAL win and
            not merely an efficient one, and it is the one people miss.

            Membership never changes everywhere at the same instant. It propagates --
            by gossip, by a config push, by a watch firing -- and for that window your
            clients hold different lists and therefore compute different owners. Every
            key in the disagreement column is a key where a write may land on one
            machine while a read looks on another.

            The size of that window is not yours to control, but the size of the
            DAMAGE is, and it is exactly the movement fraction from Experiment 1. The
            ring keeps the blast radius at roughly 1/n of your keyspace. Modulo makes
            almost your entire keyspace ambiguous for the whole propagation window.

            For a cache the consequence is extra misses. For stored data it is worse:
            a write to the wrong owner is invisible, and it stays invisible until the
            reader that needed it comes looking. Real systems close this with a
            coordinator that hands out one authoritative version of the ring -- which
            is exactly the design Redis Cluster and Kafka choose instead (README §15).""");
    }

    static void note(String s) { System.out.print(("\n" + s).indent(2)); }
}
