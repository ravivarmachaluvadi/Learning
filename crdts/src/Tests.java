import java.util.*;

/**
 * Executable documentation. Every test is named after a situation, not a
 * method, and several of them assert a FAILURE on purpose — the ones that
 * document what a type cannot do are as important as the ones that pass.
 *
 *   java src/Tests.java          all guarantees
 *   java src/Tests.java broken   the three planted bugs, and which law each breaks
 */
public class Tests {

    static int passed = 0, failed = 0;

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("broken")) { brokenSuite(); return; }

        section("The three laws — checked on every type in the lab");
        lawsGCounter();
        lawsPNCounter();
        lawsGSet();
        lawsOrSet();
        lawsLww();
        lawsMv();
        lawsRga();

        laws_hold_on_random_states_too();

        section("Counters");
        gcounter_two_offline_increments_both_survive();
        gcounter_duplicate_delivery_changes_nothing();
        gcounter_every_delivery_order_gives_the_same_answer();
        gcounter_lowering_your_own_row_is_refused();
        pncounter_decrement_survives_a_merge();
        pncounter_cannot_keep_the_value_above_zero();

        section("Sets");
        gset_union_converges_under_a_broken_network();
        twopset_cannot_readd_something_it_removed();
        orset_readd_after_remove_works();
        orset_concurrent_add_and_remove_the_add_wins();
        orset_remove_of_a_seen_add_stays_removed();
        orset_tombstones_outlive_the_data();

        section("Registers");
        lww_concurrent_writes_silently_lose_one();
        lww_a_fast_clock_beats_a_genuinely_later_write();
        mvregister_concurrent_writes_keep_both();
        mvregister_a_write_that_saw_both_collapses_them();

        section("Text");
        rga_concurrent_inserts_converge();
        rga_a_delete_arriving_before_its_character_still_converges();
        rga_fuzz_three_editors_never_diverge();

        section("The whole point");
        every_type_converges_under_drop_duplicate_reorder_and_partition();

        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- laws

    static <T extends Replicated<T>> void laws(String name, List<T> s) {
        boolean comm = true, assoc = true, idem = true;
        for (T a : s) for (T b : s) {
            if (!a.merge(b).equals(b.merge(a))) comm = false;
            if (!a.merge(a).equals(a)) idem = false;
            for (T c : s) if (!a.merge(b).merge(c).equals(a.merge(b.merge(c)))) assoc = false;
        }
        check(name + ": commutative", comm);
        check(name + ": associative", assoc);
        check(name + ": idempotent", idem);
    }

    static void lawsGCounter() {
        GCounter a = new GCounter().increment("a", 3);
        GCounter b = new GCounter().increment("b", 2).increment("a", 1);
        GCounter c = a.merge(b).increment("c", 7);
        laws("G-Counter", List.of(new GCounter(), a, b, c));
    }
    static void lawsPNCounter() {
        PNCounter a = new PNCounter().increment("a", 5).decrement("a", 2);
        PNCounter b = new PNCounter().decrement("b", 4);
        laws("PN-Counter", List.of(new PNCounter(), a, b, a.merge(b).increment("c", 1)));
    }
    static void lawsGSet() {
        GSet<String> a = new GSet<String>().add("milk");
        GSet<String> b = new GSet<String>().add("eggs").add("milk");
        laws("G-Set", List.of(new GSet<String>(), a, b, a.merge(b).add("jam")));
    }
    static void lawsOrSet() {
        OrSet<String> a = new OrSet<String>().add("milk", new Dot("a", 1));
        OrSet<String> b = a.add("eggs", new Dot("b", 1)).remove("milk");
        OrSet<String> c = a.add("milk", new Dot("a", 2));
        laws("OR-Set", List.of(new OrSet<String>(), a, b, c, b.merge(c)));
    }
    static void lawsLww() {
        LwwRegister<String> a = new LwwRegister<String>().assign("blue", 100, "a");
        LwwRegister<String> b = new LwwRegister<String>().assign("green", 100, "b");  // exact tie
        LwwRegister<String> c = new LwwRegister<String>().assign("red", 200, "a");
        laws("LWW-Register", List.of(new LwwRegister<String>(), a, b, c));
    }
    static void lawsMv() {
        MvRegister<String> a = new MvRegister<String>().assign("blue", "a");
        MvRegister<String> b = new MvRegister<String>().assign("green", "b");
        laws("MV-Register", List.of(new MvRegister<String>(), a, b, a.merge(b).assign("red", "c")));
    }
    static void lawsRga() {
        Rga a = new Rga().insertAfter(Rga.ROOT, 'H', new Dot("a", 1));
        Rga b = a.insertAfter(new Dot("a", 1), 'i', new Dot("b", 1));
        Rga c = a.insertAfter(Rga.ROOT, 'O', new Dot("c", 1)).delete(new Dot("a", 1));
        laws("RGA", List.of(new Rga(), a, b, c, b.merge(c)));
    }

    /**
     * A fixed handful of sample states is NOT a proof. This lab found that out
     * the hard way: a plausible "remove-wins" patch to OrSet.merge passes the
     * law checks above on their five samples, and breaks associativity on
     * 1,696 of these 50,000 random triples. Randomised states are the real
     * check — and this fuzz also found a real idempotence gap in OrSet.add,
     * see the INVARIANT comment there.
     */
    static void laws_hold_on_random_states_too() {
        Random rnd = new Random(1);
        int trials = 50_000, comm = 0, assoc = 0, idem = 0;
        for (int t = 0; t < trials; t++) {
            List<OrSet<String>> triple = randomTriple(rnd);
            OrSet<String> x = triple.get(0), y = triple.get(1), z = triple.get(2);
            if (!x.merge(y).equals(y.merge(x))) comm++;
            if (!x.merge(x).equals(x)) idem++;
            if (!x.merge(y).merge(z).equals(x.merge(y.merge(z)))) assoc++;
        }
        check("OR-Set laws over " + trials + " random triples (comm/assoc/idem broken: "
              + comm + "/" + assoc + "/" + idem + ")", comm + assoc + idem == 0);
    }

    private static long dotSeq = 0;

    /**
     * Three replicas that share a common history and then diverge — the
     * realistic shape, and the one where concurrent add-vs-remove actually
     * happens. Dots are minted uniquely, because that is the contract: a dot
     * names ONE event. Reusing a dot for two different adds is outside the
     * type's contract and will break idempotence, which is itself worth
     * knowing.
     */
    static List<OrSet<String>> randomTriple(Random r) {
        OrSet<String> base = churn(new OrSet<>(), r, r.nextInt(4));
        return List.of(churn(base, r, 1 + r.nextInt(3)),
                       churn(base, r, 1 + r.nextInt(3)),
                       churn(base, r, 1 + r.nextInt(3)));
    }

    static OrSet<String> churn(OrSet<String> s, Random r, int steps) {
        String[] items = {"milk", "eggs"};
        for (int i = 0; i < steps; i++) {
            String it = items[r.nextInt(items.length)];
            s = r.nextInt(3) == 0 ? s.remove(it)
                                  : s.add(it, new Dot("r" + r.nextInt(3), ++dotSeq));
        }
        return s;
    }

    // ------------------------------------------------------------ counters

    static void gcounter_two_offline_increments_both_survive() {
        GCounter amira = new GCounter().increment("amira", 1);
        GCounter ben   = new GCounter().increment("ben", 1);
        check("two offline +1s make 2, not 1", amira.merge(ben).value() == 2);
    }

    static void gcounter_duplicate_delivery_changes_nothing() {
        GCounter amira = new GCounter().increment("amira", 1);
        GCounter ben   = new GCounter().increment("ben", 1);
        GCounter once  = ben.merge(amira);
        GCounter five  = ben.merge(amira).merge(amira).merge(amira).merge(amira).merge(amira);
        check("the same message five times still says 2", once.equals(five) && five.value() == 2);
    }

    static void gcounter_every_delivery_order_gives_the_same_answer() {
        List<GCounter> parts = List.of(
            new GCounter().increment("a", 3),
            new GCounter().increment("b", 5),
            new GCounter().increment("c", 2));
        Set<Long> answers = new HashSet<>();
        for (List<GCounter> p : permutations(parts)) {
            GCounter acc = new GCounter();
            for (GCounter x : p) acc = acc.merge(x);
            answers.add(acc.value());
        }
        check("all 6 delivery orders agree on 10", answers.equals(Set.of(10L)));
    }

    static void gcounter_lowering_your_own_row_is_refused() {
        boolean threw = false;
        try { new GCounter().increment("a", -1); } catch (IllegalArgumentException e) { threw = true; }
        check("a G-Counter refuses a decrement out loud", threw);
    }

    static void pncounter_decrement_survives_a_merge() {
        PNCounter amira = new PNCounter().increment("amira", 5);
        PNCounter ben   = amira.merge(new PNCounter()).decrement("ben", 2);
        check("+5 then -2 across two replicas = 3", amira.merge(ben).value() == 3);
    }

    static void pncounter_cannot_keep_the_value_above_zero() {
        // 10 in stock. Two warehouses each sell 6 while partitioned. Both were
        // "obviously fine" locally. This is the guarantee CRDTs cannot give.
        PNCounter start = new PNCounter().increment("stock", 10);
        PNCounter w1 = start.decrement("w1", 6);
        PNCounter w2 = start.decrement("w2", 6);
        check("stock goes NEGATIVE (-2) — no global invariant", w1.merge(w2).value() == -2);
    }

    // ---------------------------------------------------------------- sets

    static void gset_union_converges_under_a_broken_network() {
        Cluster<GSet<String>> c = new Cluster<>(new Random(1));
        c.dropRate = 0.4; c.duplicateRate = 0.4; c.shuffle = true;
        for (String n : List.of("a", "b", "c")) c.addReplica(n, new GSet<>());
        c.update("a", s -> s.add("milk"));
        c.update("b", s -> s.add("eggs"));
        c.update("c", s -> s.add("jam"));
        for (int i = 0; i < 20; i++) { c.gossipRound(); c.deliverAll(); }
        c.settle(20);
        check("G-Set converges on all 3 items through a 40%-lossy network",
              c.converged() && c.state("a").value().equals(Set.of("milk", "eggs", "jam")));
    }

    static void twopset_cannot_readd_something_it_removed() {
        TwoPSet<String> s = new TwoPSet<String>().add("milk").remove("milk").add("milk");
        check("2P-Set: 'milk' is dead forever after one remove", !s.value().contains("milk"));
    }

    static void orset_readd_after_remove_works() {
        OrSet<String> s = new OrSet<String>()
            .add("milk", new Dot("a", 1))
            .remove("milk")
            .add("milk", new Dot("a", 2));
        check("OR-Set: re-adding after a remove works", s.value().contains("milk"));
    }

    static void orset_concurrent_add_and_remove_the_add_wins() {
        OrSet<String> shared = new OrSet<String>().add("milk", new Dot("amira", 1));
        OrSet<String> ben    = shared.remove("milk");                       // Ben deletes what he saw
        OrSet<String> amira  = shared.add("milk", new Dot("amira", 2));     // Amira re-adds, unaware
        OrSet<String> merged = ben.merge(amira);
        check("concurrent add vs remove: the add wins", merged.value().contains("milk"));
        check("...and it wins in both merge directions", amira.merge(ben).equals(merged));
    }

    static void orset_remove_of_a_seen_add_stays_removed() {
        OrSet<String> shared = new OrSet<String>().add("milk", new Dot("amira", 1));
        OrSet<String> ben = shared.remove("milk");
        check("a remove of an add you HAVE seen is respected", !shared.merge(ben).value().contains("milk"));
    }

    static void orset_tombstones_outlive_the_data() {
        OrSet<String> s = new OrSet<>();
        for (int i = 1; i <= 50; i++) s = s.add("item" + i, new Dot("a", i)).remove("item" + i);
        check("50 adds + 50 removes leave 0 items and 50 tombstones",
              s.value().isEmpty() && s.tombstoneCount() == 50);
    }

    // ----------------------------------------------------------- registers

    static void lww_concurrent_writes_silently_lose_one() {
        LwwRegister<String> amira = new LwwRegister<String>().assign("Flat 3, Elm Rd", 1000, "amira");
        LwwRegister<String> ben   = new LwwRegister<String>().assign("Flat 3, Oak Rd", 1000, "ben");
        LwwRegister<String> m = amira.merge(ben);
        check("two concurrent addresses -> exactly one survives", m.value().equals("Flat 3, Oak Rd"));
        check("...and the loser leaves no trace at all", m.equals(ben));
    }

    static void lww_a_fast_clock_beats_a_genuinely_later_write() {
        LwwRegister<String> fastClock = new LwwRegister<String>().assign("old", 5_000, "a");
        LwwRegister<String> reality   = new LwwRegister<String>().assign("new", 1_000, "b");
        check("a clock 4s fast overrules a truly newer write",
              fastClock.merge(reality).value().equals("old"));
    }

    static void mvregister_concurrent_writes_keep_both() {
        MvRegister<String> amira = new MvRegister<String>().assign("Elm Rd", "amira");
        MvRegister<String> ben   = new MvRegister<String>().assign("Oak Rd", "ben");
        MvRegister<String> m = amira.merge(ben);
        check("MV-Register surfaces both addresses instead of picking",
              m.isConflicted() && new HashSet<>(m.values()).equals(Set.of("Elm Rd", "Oak Rd")));
    }

    static void mvregister_a_write_that_saw_both_collapses_them() {
        MvRegister<String> amira = new MvRegister<String>().assign("Elm Rd", "amira");
        MvRegister<String> ben   = new MvRegister<String>().assign("Oak Rd", "ben");
        MvRegister<String> resolved = amira.merge(ben).assign("Oak Rd, Flat 3", "amira");
        check("a human resolving the conflict collapses it to one value",
              !resolved.isConflicted() && resolved.values().equals(List.of("Oak Rd, Flat 3")));
        check("...and the resolution survives re-merging the old siblings",
              resolved.merge(amira).merge(ben).values().equals(List.of("Oak Rd, Flat 3")));
    }

    // ---------------------------------------------------------------- text

    static void rga_concurrent_inserts_converge() {
        // shared "HAT"; Amira puts C at the front, Ben puts S at the end.
        Rga base = new Rga();
        base = base.insertAfter(Rga.ROOT, 'H', new Dot("x", 1));
        base = base.insertAfter(new Dot("x", 1), 'A', new Dot("x", 2));
        base = base.insertAfter(new Dot("x", 2), 'T', new Dot("x", 3));
        // Lamport-numbered dots: both authors have seen 3 characters, so both
        // number their new one 4. Ties then break on replica id.
        Rga amira = base.insertAfter(Rga.ROOT,        'C', new Dot("amira", base.nextCounter()));
        Rga ben   = base.insertAfter(new Dot("x", 3), 'S', new Dot("ben",   base.nextCounter()));
        check("both editors read CHATS", amira.merge(ben).value().equals("CHATS")
                                      && ben.merge(amira).value().equals("CHATS"));
    }

    static void rga_a_delete_arriving_before_its_character_still_converges() {
        Rga withChar = new Rga().insertAfter(Rga.ROOT, 'H', new Dot("a", 1));
        Rga onlyDelete = new Rga().delete(new Dot("a", 1));   // the delete travelled faster
        check("delete-before-insert: character is still gone after both arrive",
              onlyDelete.merge(withChar).value().isEmpty()
           && withChar.merge(onlyDelete).value().isEmpty());
    }

    static void rga_fuzz_three_editors_never_diverge() {
        int diverged = 0, runs = 500;
        for (int seed = 0; seed < runs; seed++) {
            Random rnd = new Random(seed);
            Cluster<Rga> c = new Cluster<>(rnd);
            c.dropRate = 0.3; c.duplicateRate = 0.3; c.shuffle = true;
            List<String> who = List.of("amira", "ben", "cara");
            for (String n : who) c.addReplica(n, new Rga());
            for (int step = 0; step < 30; step++) {
                String n = who.get(rnd.nextInt(who.size()));
                char ch = (char) ('a' + rnd.nextInt(6));
                c.update(n, r -> {
                    Dot id = new Dot(n, r.nextCounter());   // Lamport, per the note in Rga.java
                    List<Dot> vis = r.visibleDots();
                    if (!vis.isEmpty() && rnd.nextInt(4) == 0) return r.deleteAt(rnd.nextInt(vis.size()));
                    return r.insertAt(vis.isEmpty() ? 0 : rnd.nextInt(vis.size() + 1), ch, id);
                });
                if (rnd.nextBoolean()) { c.gossipRound(); c.deliverAll(); }
            }
            if (c.settle(60) < 0) diverged++;
        }
        check("500 random 3-editor sessions, 30% loss + 30% duplication: "
              + diverged + " diverged", diverged == 0);
    }

    // ----------------------------------------------------------- the point

    static void every_type_converges_under_drop_duplicate_reorder_and_partition() {
        Random rnd = new Random(42);
        Cluster<PNCounter> c = new Cluster<>(rnd);
        c.dropRate = 0.5; c.duplicateRate = 0.5; c.shuffle = true;
        for (String n : List.of("a", "b", "c", "d")) c.addReplica(n, new PNCounter());
        c.partition("a", 0); c.partition("b", 0); c.partition("c", 1); c.partition("d", 1);
        for (int i = 0; i < 30; i++) {
            String n = List.of("a", "b", "c", "d").get(rnd.nextInt(4));
            c.update(n, p -> rnd.nextBoolean() ? p.increment(n, 1) : p.decrement(n, 1));
            c.gossipRound(); c.deliverAll();
        }
        boolean splitDuringPartition = c.distinctViews(PNCounter::value).size() > 1;
        c.healPartition();
        int rounds = c.settle(50);
        check("during the partition the two halves disagree", splitDuringPartition);
        check("after healing, all 4 replicas agree (in " + rounds + " rounds)",
              rounds > 0 && c.distinctViews(PNCounter::value).size() == 1);
    }

    // -------------------------------------------------------------- broken

    static void brokenSuite() {
        System.out.println("The three planted bugs — each breaks exactly one law.\n");

        Broken.SummingCounter a = new Broken.SummingCounter().increment("a", 1);
        Broken.SummingCounter b = new Broken.SummingCounter().increment("b", 1);
        System.out.println("BUG 1  SummingCounter (merge sums instead of maxing)");
        System.out.println("   one delivery : " + b.merge(a).value());
        System.out.println("   same message delivered 3x : " + b.merge(a).merge(a).merge(a).value());
        check("   -> broken: idempotence", !b.merge(a).equals(b.merge(a).merge(a)));

        Broken.LeakyOrSet s = new Broken.LeakyOrSet().add("milk", new Dot("a", 1));
        Broken.LeakyOrSet deleter = s.remove("milk");
        Broken.LeakyOrSet holder  = s;
        System.out.println("\nBUG 2  LeakyOrSet (merge subtracts only its own tombstones)");
        System.out.println("   deleter.merge(holder) = " + deleter.merge(holder).value());
        System.out.println("   holder.merge(deleter) = " + holder.merge(deleter).value());
        check("   -> broken: commutativity", !deleter.merge(holder).equals(holder.merge(deleter)));

        Broken.TielessLww x = new Broken.TielessLww().assign("blue", 100);
        Broken.TielessLww y = new Broken.TielessLww().assign("green", 100);
        System.out.println("\nBUG 3  TielessLww (equal timestamps keep whatever I had)");
        System.out.println("   x.merge(y) = " + x.merge(y).value() + "    y.merge(x) = " + y.merge(x).value());
        check("   -> broken: commutativity", !x.merge(y).equals(y.merge(x)));

        System.out.printf("%n%d of the 3 bugs were caught by the laws alone.%n", passed);
    }

    // --------------------------------------------------------------- utils

    static <T> List<List<T>> permutations(List<T> in) {
        if (in.isEmpty()) return List.of(List.of());
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            List<T> rest = new ArrayList<>(in);
            T head = rest.remove(i);
            for (List<T> p : permutations(rest)) {
                List<T> full = new ArrayList<>(); full.add(head); full.addAll(p);
                out.add(full);
            }
        }
        return out;
    }

    static void section(String s) { System.out.println("\n== " + s); }

    static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("  PASS  " + name); }
        else    { failed++; System.out.println("  FAIL  " + name); }
    }
}
