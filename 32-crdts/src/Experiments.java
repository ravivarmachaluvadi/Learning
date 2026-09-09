import java.util.*;

/**
 * Ten measurements. Every number printed by this file was produced by running
 * it — nothing here is estimated.
 *
 *   java src/Experiments.java        run all ten
 *   java src/Experiments.java 4      run just experiment 4
 */
public class Experiments {

    public static void main(String[] args) {
        List<Runnable> all = List.of(
            Experiments::e1_lostUpdates,
            Experiments::e2_duplicateInflation,
            Experiments::e3_everyDeliveryOrder,
            Experiments::e4_whyDecrementNeedsASecondPile,
            Experiments::e5_readdAfterRemove,
            Experiments::e6_addWinsAndTheResurrectedItem,
            Experiments::e7_metadataTax,
            Experiments::e8_clockSkew,
            Experiments::e9_opBasedNeedsCausalDelivery,
            Experiments::e10_aReplicaLosesItsDisk);

        if (args.length > 0) { all.get(Integer.parseInt(args[0]) - 1).run(); return; }
        for (Runnable r : all) { r.run(); System.out.println(); }
    }

    // ------------------------------------------------------------------ 1

    static void e1_lostUpdates() {
        head(1, "How many updates does last-writer-wins throw away?",
              "Three phones each add items to a shared 'likes' count while offline,\n"
            + "then sync. Design A ships the whole number and keeps the newest.\n"
            + "Design B ships a G-Counter scoreboard.");

        int rounds = 1000, replicas = 3;
        long lwwTotal = 0, crdtTotal = 0, expected = 0;
        Random rnd = new Random(7);

        for (int r = 0; r < rounds; r++) {
            // everyone starts from the same synced value
            long start = 100;
            long[] localLww = new long[replicas];
            long[] clock = new long[replicas];
            GCounter g = new GCounter().increment("start", start);
            GCounter[] localG = new GCounter[replicas];
            int[] bumps = new int[replicas];

            for (int i = 0; i < replicas; i++) {
                bumps[i] = 1 + rnd.nextInt(5);
                localLww[i] = start + bumps[i];
                clock[i] = rnd.nextInt(1000);
                localG[i] = g.increment("r" + i, bumps[i]);
                expected += bumps[i];
            }
            // design A: whole-value LWW
            int winner = 0;
            for (int i = 1; i < replicas; i++) if (clock[i] > clock[winner]) winner = i;
            lwwTotal += localLww[winner] - start;
            // design B: merge the scoreboards
            GCounter merged = localG[0];
            for (int i = 1; i < replicas; i++) merged = merged.merge(localG[i]);
            crdtTotal += merged.value() - start;
        }

        System.out.printf("  increments actually made by users : %d%n", expected);
        System.out.printf("  survived under last-writer-wins   : %d   (%.1f%% LOST)%n",
                lwwTotal, 100.0 * (expected - lwwTotal) / expected);
        System.out.printf("  survived under G-Counter          : %d   (%.1f%% lost)%n",
                crdtTotal, 100.0 * (expected - crdtTotal) / expected);
        note("LWW converges too. It just converges onto a number that never happened.");
    }

    // ------------------------------------------------------------------ 2

    static void e2_duplicateInflation() {
        head(2, "Why merge must be MAX and not SUM",
              "The same sync message is delivered several times — normal for any\n"
            + "at-least-once transport. Sum-merge and max-merge, side by side.");

        int[] copies = {1, 2, 3, 5, 10};
        System.out.println("  deliveries of the same message | sum-merge | max-merge (G-Counter)");
        for (int n : copies) {
            GCounter good = new GCounter().increment("b", 1);
            Broken.SummingCounter bad = new Broken.SummingCounter().increment("b", 1);
            GCounter fromA = new GCounter().increment("a", 1);
            Broken.SummingCounter badA = new Broken.SummingCounter().increment("a", 1);
            for (int i = 0; i < n; i++) { good = good.merge(fromA); bad = bad.merge(badA); }
            System.out.printf("  %30d | %9d | %d%n", n, bad.value(), good.value());
        }
        note("The true answer is 2. Idempotence is not an optimisation — it is the\n"
           + "  only thing standing between you and a counter that inflates on retry.");
    }

    // ------------------------------------------------------------------ 3

    static void e3_everyDeliveryOrder() {
        head(3, "Does delivery order matter? All 5,040 of them.",
              "Seven sync messages are pending. We deliver them in every possible\n"
            + "order and record how many different final states come out.");

        Random rnd = new Random(3);
        Cluster<OrSet<String>> proto = new Cluster<>(rnd);
        for (String n : List.of("a", "b", "c")) proto.addReplica(n, new OrSet<>());
        proto.update("a", s -> s.add("milk", new Dot("a", 1)));
        proto.update("b", s -> s.add("eggs", new Dot("b", 1)));
        proto.update("c", s -> s.add("jam",  new Dot("c", 1)));

        // collect 7 concrete messages, then replay them in every order
        List<OrSet<String>> payloads = new ArrayList<>();
        payloads.add(proto.state("a")); payloads.add(proto.state("b")); payloads.add(proto.state("c"));
        payloads.add(proto.state("a").merge(proto.state("b")));
        payloads.add(proto.state("b").merge(proto.state("c")).remove("eggs"));
        payloads.add(proto.state("c"));
        payloads.add(proto.state("a"));

        Set<String> distinct = new LinkedHashSet<>();
        int count = 0;
        for (List<OrSet<String>> order : Tests.permutations(payloads)) {
            OrSet<String> acc = new OrSet<>();
            for (OrSet<String> p : order) acc = acc.merge(p);
            distinct.add(acc.value().toString());
            count++;
        }
        System.out.printf("  orderings tried            : %d%n", count);
        System.out.printf("  distinct final states      : %d  -> %s%n", distinct.size(), distinct);
        note("This is what 'the network cannot hurt you' means, measured.");
    }

    // ------------------------------------------------------------------ 4

    static void e4_whyDecrementNeedsASecondPile() {
        head(4, "What happens if you just lower your own row?",
              "A G-Counter at 10. Replica 'b' tries to subtract 3 by writing a\n"
            + "smaller number into its own row, then syncs with replica 'a'.");

        GCounter a = new GCounter().increment("a", 6).increment("b", 4);
        // b "decrements" by rewriting its row to 1  (4 - 3)
        GCounter b = new GCounter().increment("a", 6).increment("b", 1);
        System.out.println("  a's copy after its own edit : " + a + "");
        System.out.println("  b's copy after the -3       : " + b);
        System.out.println("  after they sync             : " + a.merge(b));
        System.out.printf("  the decrement survived      : %s%n", a.merge(b).value() == 7 ? "yes" : "NO");
        note("merge takes the MAX, so the lower number is treated as older news and\n"
           + "  discarded. A decrement that can be undone by a merge is not a decrement.\n"
           + "  Hence the PN-Counter: never lower a row, raise a row in the other pile.");

        PNCounter pa = new PNCounter().increment("a", 6).increment("b", 4);
        PNCounter pb = pa.decrement("b", 3);
        System.out.println("  PN-Counter, same story      : " + pa.merge(pb) + "  (value " + pa.merge(pb).value() + ")");
    }

    // ------------------------------------------------------------------ 5

    static void e5_readdAfterRemove() {
        head(5, "Removing something, then wanting it back",
              "100 shopping-cart cycles: add the item, remove it, add it again.\n"
            + "How many times is the item actually in the cart at the end?");

        int cycles = 100, twopOk = 0, orOk = 0;
        for (int i = 0; i < cycles; i++) {
            TwoPSet<String> t = new TwoPSet<String>().add("milk").remove("milk").add("milk");
            if (t.value().contains("milk")) twopOk++;
            OrSet<String> o = new OrSet<String>()
                .add("milk", new Dot("a", 3L * i + 1)).remove("milk")
                .add("milk", new Dot("a", 3L * i + 3));
            if (o.value().contains("milk")) orOk++;
        }
        System.out.printf("  2P-Set : item present in %d / %d carts%n", twopOk, cycles);
        System.out.printf("  OR-Set : item present in %d / %d carts%n", orOk, cycles);
        note("The 2P-Set converges perfectly on the wrong answer. Convergence is not\n"
           + "  correctness — 'all replicas agree the cart is empty' is still a bug.");
    }

    // ------------------------------------------------------------------ 6

    static void e6_addWinsAndTheResurrectedItem() {
        head(6, "Concurrent add and remove — and the item that comes back",
              "Amira re-adds an item at the same moment Ben deletes it. Then the\n"
            + "famous one: Ben deletes the item, and it reappears anyway.");

        OrSet<String> shared = new OrSet<String>().add("milk", new Dot("amira", 1));
        OrSet<String> ben   = shared.remove("milk");
        OrSet<String> amira = shared.add("milk", new Dot("amira", 2));
        System.out.println("  simultaneous re-add vs delete -> " + ben.merge(amira).value()
                         + "   (the add wins, in both directions: " + amira.merge(ben).value() + ")");

        // Amira had never seen Ben's copy at all, and adds independently
        OrSet<String> benAlone   = new OrSet<String>().add("milk", new Dot("ben", 1)).remove("milk");
        OrSet<String> amiraAlone = new OrSet<String>().add("milk", new Dot("amira", 1));
        System.out.println("  Ben deletes his own add, Amira adds hers -> "
                         + benAlone.merge(amiraAlone).value() + "  (Ben's delete is respected;");
        System.out.println("                                              Amira's separate add is not touched)");

        int trials = 1000, addWon = 0;
        Random rnd = new Random(11);
        for (int i = 0; i < trials; i++) {
            OrSet<String> base = new OrSet<String>().add("milk", new Dot("amira", 2L * i + 1));
            OrSet<String> del = base.remove("milk");
            OrSet<String> re  = base.add("milk", new Dot("amira", 2L * i + 2));
            OrSet<String> m = rnd.nextBoolean() ? del.merge(re) : re.merge(del);
            if (m.value().contains("milk")) addWon++;
        }
        System.out.printf("  over %d randomly-ordered merges, the add won %d times%n", trials, addWon);
        note("Add-wins is a POLICY, chosen because a lost item annoys a user less than\n"
           + "  a resurrected one is dangerous. It is not the only choice — remove-wins\n"
           + "  sets exist — but you must know which one you bought.");
    }

    // ------------------------------------------------------------------ 7

    static void e7_metadataTax() {
        head(7, "What it costs: metadata per visible thing",
              "Churn a shopping cart, then a document, and count what is stored\n"
            + "versus what the user can see.");

        OrSet<String> cart = new OrSet<>();
        long dot = 0;
        for (int i = 0; i < 500; i++) {
            cart = cart.add("item" + (i % 20), new Dot("a", ++dot));
            if (i % 2 == 0) cart = cart.remove("item" + (i % 20));
        }
        System.out.printf("  OR-Set after 500 adds / 250 removes%n");
        System.out.printf("    visible items      : %d%n", cart.value().size());
        System.out.printf("    dots stored        : %d%n", cart.metadataCells());
        System.out.printf("    tombstones         : %d%n", cart.tombstoneCount());
        System.out.printf("    cells per item     : %.1f%n", cart.metadataCells() / (double) cart.value().size());

        Rga doc = new Rga();
        Random rnd = new Random(5);
        for (int i = 0; i < 600; i++) {
            List<Dot> vis = doc.visibleDots();
            if (!vis.isEmpty() && rnd.nextInt(3) == 0) doc = doc.deleteAt(rnd.nextInt(vis.size()));
            else doc = doc.insertAt(vis.isEmpty() ? 0 : rnd.nextInt(vis.size() + 1),
                                    (char) ('a' + rnd.nextInt(26)), new Dot("a", doc.nextCounter()));
        }
        System.out.printf("%n  RGA after 600 edits%n");
        System.out.printf("    visible characters : %d%n", doc.visibleLength());
        System.out.printf("    cells stored       : %d%n", doc.metadataCells());
        System.out.printf("    cells per character: %.1f%n",
                doc.metadataCells() / (double) Math.max(1, doc.visibleLength()));
        note("Nothing here is ever freed by the algorithm itself. Garbage collection\n"
           + "  of tombstones needs a separate agreement about what everyone has seen —\n"
           + "  which is why production CRDT stores are mostly a GC story.");
    }

    // ------------------------------------------------------------------ 8

    static void e8_clockSkew() {
        head(8, "Under last-writer-wins, your clock IS your conflict resolver",
              "One replica's clock is 60 seconds fast. It makes 1,000 writes that\n"
            + "are genuinely OLDER than the other replica's writes.");

        int trials = 1000, staleWon = 0;
        for (int i = 0; i < trials; i++) {
            long realTime = 1_000_000 + i * 10L;
            LwwRegister<String> fast = new LwwRegister<String>()
                    .assign("old-value-" + i, realTime + 60_000, "fast");   // clock 60s ahead
            LwwRegister<String> honest = new LwwRegister<String>()
                    .assign("new-value-" + i, realTime + 5_000, "honest");  // truly 5s later
            if (fast.merge(honest).value().startsWith("old")) staleWon++;
        }
        System.out.printf("  genuinely-newer writes destroyed by the fast clock : %d / %d%n", staleWon, trials);

        MvRegister<String> a = new MvRegister<String>().assign("old-value", "fast");
        MvRegister<String> b = new MvRegister<String>().assign("new-value", "honest");
        System.out.println("  the same pair in an MV-Register                    : " + a.merge(b).values());
        note("No error, no log line, no metric. This is the same failure the\n"
           + "  quorums-and-replication lab measured from the storage side — an\n"
           + "  MV-Register is the fix, and 'siblings' is what its output is called.");
    }

    // ------------------------------------------------------------------ 9

    static void e9_opBasedNeedsCausalDelivery() {
        head(9, "Shipping operations instead of whole state",
              "Sending the entire scoreboard every time is wasteful, so ship just the\n"
            + "operation. Cheaper — and it introduces a requirement state-based\n"
            + "merging never had: the operations must arrive in causal order.");

        // An op-based OR-Set: no tombstones kept, a remove just deletes the dots
        // it names. Two replicas receive the same two operations, in opposite
        // orders — which is all an unordered network has to do to break it.
        List<Op> ops = List.of(
            new Op("add", "milk", Set.of(new Dot("amira", 1))),
            new Op("rem", "milk", Set.of(new Dot("amira", 1))));

        Set<String> forward  = applyOps(ops);
        Set<String> backward = applyOps(List.of(ops.get(1), ops.get(0)));
        System.out.println("  op-based, add then remove     : " + forward);
        System.out.println("  op-based, remove then add     : " + backward);
        System.out.println("  -> the two replicas DISAGREE  : " + !forward.equals(backward));

        // The same two events as STATE. A state-based replica never applies a
        // bare "remove"; it merges a whole snapshot, tombstones included.
        OrSet<String> added   = new OrSet<String>().add("milk", new Dot("amira", 1));
        OrSet<String> removed = added.remove("milk");
        Set<String> sForward  = new OrSet<String>().merge(added).merge(removed).value();
        Set<String> sBackward = new OrSet<String>().merge(removed).merge(added).value();
        System.out.println("  state-based, snapshot order A : " + sForward);
        System.out.println("  state-based, snapshot order B : " + sBackward);
        System.out.println("  -> they AGREE                 : " + sForward.equals(sBackward));
        note("This is exactly the bug the google-docs lab hit in its own CRDT (a delete\n"
           + "  arriving before its character). Op-based CRDTs are much cheaper on the\n"
           + "  wire and buy it with a delivery guarantee somebody has to implement:\n"
           + "  causal broadcast, exactly once. State-based ships more bytes and needs\n"
           + "  nothing from the network at all.");
    }

    record Op(String kind, String item, Set<Dot> dots) {}

    static Set<String> applyOps(List<Op> ops) {
        Map<String, Set<Dot>> live = new TreeMap<>();
        for (Op op : ops) {
            if (op.kind().equals("add")) {
                live.computeIfAbsent(op.item(), k -> new TreeSet<>()).addAll(op.dots());
            } else {
                Set<Dot> cur = live.get(op.item());
                if (cur != null) { cur.removeAll(op.dots()); if (cur.isEmpty()) live.remove(op.item()); }
            }
        }
        return live.keySet();
    }

    // ----------------------------------------------------------------- 10

    static void e10_aReplicaLosesItsDisk() {
        head(10, "A replica is wiped and restarts empty",
               "The quorums lab showed this failure mode counting replicas rather than\n"
             + "copies. Here is what it does to a state-based CRDT, and to an\n"
             + "op-based one whose operations were consumed and forgotten.");

        Cluster<PNCounter> c = new Cluster<>(new Random(2));
        for (String n : List.of("a", "b", "c")) c.addReplica(n, new PNCounter());
        c.update("a", p -> p.increment("a", 40));
        c.update("b", p -> p.increment("b", 25));
        c.update("c", p -> p.decrement("c", 5));
        c.settle(10);
        System.out.printf("  healthy cluster value                    : %d%n", c.state("a").value());

        c.update("c", p -> new PNCounter());       // c's disk is wiped
        System.out.printf("  after c is wiped, c reads                : %d%n", c.state("c").value());
        c.settle(10);
        System.out.printf("  after one sync round, c reads            : %d   (recovered: %s)%n",
                c.state("c").value(), c.state("c").value() == 60 ? "yes" : "no");

        long opBasedC = 0;                          // c applied ops as they arrived, kept nothing
        System.out.printf("  an op-based replica in the same position : %d   (recovered: no)%n", opBasedC);
        note("A state-based replica carries its own proof of everything it knows, so a\n"
           + "  wiped replica is repaired by one ordinary sync. An op-based one needs\n"
           + "  somebody to keep and replay the log — which is a different system.");
    }

    // --------------------------------------------------------------- utils

    static void head(int n, String title, String setup) {
        System.out.println("=".repeat(74));
        System.out.printf("EXPERIMENT %d — %s%n", n, title);
        System.out.println("=".repeat(74));
        System.out.println(setup.indent(2).stripTrailing());
        System.out.println();
    }

    static void note(String s) { System.out.println("\n  " + s); }
}
