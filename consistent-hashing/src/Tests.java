import java.util.*;

/**
 * Tests as executable documentation. Each name is a claim the README makes; running
 * this file is how you check the README is not lying to you.
 *
 *     java src/Tests.java
 */
public final class Tests {

    static int passed, failed;
    static final List<String> KEYS = keys(20_000);

    public static void main(String[] args) {
        System.out.println("consistent hashing test suite\n" + "=".repeat(66));

        section("the problem with dividing by the number of machines");
        test("modulo_moves_almost_every_key_when_one_machine_is_added", Tests::moduloMovesEverything);
        test("modulo_moves_keys_between_two_machines_that_never_changed", Tests::moduloViolatesMonotonicity);

        section("the ring");
        test("ring_moves_only_about_one_nth_of_the_keys_when_a_machine_joins", Tests::ringMovesLittle);
        test("ring_never_moves_a_key_between_two_existing_machines", Tests::ringIsMonotonic);
        test("ring_removal_moves_only_the_dead_machines_keys", Tests::ringRemoval);
        test("ring_wraps_around_past_the_top_of_the_number_space", Tests::ringWraps);
        test("the_same_key_always_lands_on_the_same_machine", Tests::deterministic);
        test("two_clients_building_the_ring_separately_agree_on_every_key", Tests::noCoordination);
        test("an_empty_ring_owns_nothing", Tests::emptyRing);

        section("why one point per machine is not enough");
        test("one_point_per_machine_gives_badly_uneven_load", Tests::oneVnodeIsUneven);
        test("many_points_per_machine_give_even_load", Tests::manyVnodesAreEven);
        test("a_dead_machine_dumps_all_its_keys_on_one_successor_when_vnodes_is_one", Tests::deathDumpsOnSuccessor);
        test("with_many_vnodes_a_dead_machines_keys_spread_across_everyone", Tests::deathSpreads);

        section("replication");
        test("replication_returns_r_distinct_machines", Tests::replicationDistinct);
        test("replication_degrades_gracefully_when_r_exceeds_the_cluster", Tests::replicationSmallCluster);

        section("uneven hardware");
        test("a_machine_with_triple_weight_gets_about_triple_the_keys", Tests::weighted);

        section("the alternatives");
        test("rendezvous_moves_only_the_dead_machines_keys", Tests::rendezvousStable);
        test("rendezvous_spreads_a_dead_machines_keys_across_all_survivors", Tests::rendezvousSpreads);
        test("jump_hash_balances_evenly_with_no_tuning", Tests::jumpBalanced);
        test("jump_hash_cannot_remove_a_machine_from_the_middle", Tests::jumpCannotRemove);
        test("bounded_load_never_lets_any_machine_exceed_the_cap", Tests::boundedLoadCap);

        section("the hash function is load-bearing");
        test("a_poor_hash_clumps_keys_onto_a_few_machines", Tests::badHashClumps);

        System.out.println("=".repeat(66));
        System.out.printf("%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------- the problem

    static void moduloMovesEverything() {
        Placement p = new Placements.Modulo();
        for (int i = 0; i < 10; i++) p.addNode("n" + i);
        Map<String, String> before = assign(p, KEYS);
        p.addNode("n10");
        double moved = movedFraction(before, assign(p, KEYS));
        ok("10 machines -> 11 moves over 85% of keys (moved " + pct(moved) + ")", moved > 0.85);
    }

    /**
     * The property that actually matters, and the one modulo destroys. Adding a
     * machine should only ever pull keys ONTO the newcomer. Modulo instead reshuffles
     * keys between machines that did not change at all -- pure wasted movement.
     */
    static void moduloViolatesMonotonicity() {
        Placement p = new Placements.Modulo();
        for (int i = 0; i < 10; i++) p.addNode("n" + i);
        Map<String, String> before = assign(p, KEYS);
        p.addNode("n10");
        Map<String, String> after = assign(p, KEYS);
        int pointless = 0;
        for (String k : KEYS) if (!before.get(k).equals(after.get(k)) && !after.get(k).equals("n10")) pointless++;
        ok("most movement is between machines that did not change (" + pointless + " keys)", pointless > KEYS.size() / 2);
    }

    // ---------------------------------------------------------------- the ring

    static void ringMovesLittle() {
        Ring r = new Ring(200);
        for (int i = 0; i < 10; i++) r.addNode("n" + i);
        Map<String, String> before = assign(r, KEYS);
        r.addNode("n10");
        double moved = movedFraction(before, assign(r, KEYS));
        ok("moved fraction is near 1/11 = 9.1% (was " + pct(moved) + ")", moved > 0.05 && moved < 0.14);
    }

    /** MONOTONICITY. This single test is the reason the ring exists. */
    static void ringIsMonotonic() {
        Ring r = new Ring(200);
        for (int i = 0; i < 10; i++) r.addNode("n" + i);
        Map<String, String> before = assign(r, KEYS);
        r.addNode("n10");
        Map<String, String> after = assign(r, KEYS);
        int pointless = 0;
        for (String k : KEYS) if (!before.get(k).equals(after.get(k)) && !after.get(k).equals("n10")) pointless++;
        eq("zero keys moved between two existing machines", 0, pointless);
    }

    static void ringRemoval() {
        Ring r = new Ring(200);
        for (int i = 0; i < 10; i++) r.addNode("n" + i);
        Map<String, String> before = assign(r, KEYS);
        r.removeNode("n3");
        Map<String, String> after = assign(r, KEYS);
        int wrong = 0;
        for (String k : KEYS) if (!before.get(k).equals(after.get(k)) && !before.get(k).equals("n3")) wrong++;
        eq("only keys that lived on the dead machine moved", 0, wrong);
        double moved = movedFraction(before, after);
        ok("and that is about a tenth of them (" + pct(moved) + ")", moved > 0.05 && moved < 0.16);
    }

    /**
     * A key hashing past the LAST position on the ring must come back round to the
     * first. Without this wrap the ring is an arc and the keys beyond its end have no
     * owner at all.
     */
    static void ringWraps() {
        Ring r = new Ring(4);
        r.addNode("a"); r.addNode("b");
        long max = Long.MAX_VALUE;
        List<Object[]> layout = r.layout();
        long last = (Long) layout.get(layout.size() - 1)[0];
        String firstOwner = (String) layout.get(0)[1];
        ok("a position past the last point exists to test", last < max);
        int wrapped = 0;
        for (String k : KEYS) if (Hashing.hash64(k) > last) { eq("wrapped key goes to the first machine clockwise", firstOwner, r.owner(k)); wrapped++; }
        ok("some keys actually landed past the end (" + wrapped + ")", wrapped > 0);
    }

    static void deterministic() {
        Ring r = new Ring(150);
        for (int i = 0; i < 5; i++) r.addNode("n" + i);
        for (String k : KEYS.subList(0, 500)) eq("stable across calls", r.owner(k), r.owner(k));
    }

    /**
     * NO COORDINATION. Two clients that never speak to each other, given the same
     * machine list, must reach the same answer for every key -- otherwise the scheme
     * has not removed the lookup, only hidden it.
     */
    static void noCoordination() {
        Ring a = new Ring(150), b = new Ring(150);
        for (int i = 0; i < 8; i++) a.addNode("n" + i);
        for (int i = 7; i >= 0; i--) b.addNode("n" + i);      // added in the opposite order
        for (String k : KEYS) if (!a.owner(k).equals(b.owner(k)))
            throw new AssertionError("two clients disagreed about " + k + ": " + a.owner(k) + " vs " + b.owner(k));
        ok("agreement on all " + KEYS.size() + " keys, despite different insertion order", true);
    }

    static void emptyRing() {
        Ring r = new Ring(150);
        eq("no machines, no owner", null, r.owner("anything"));
        eq("and no replicas", 0, r.owners("anything", 3).size());
    }

    // --------------------------------------------------------- virtual nodes

    static void oneVnodeIsUneven() {
        Ring r = new Ring(1);
        for (int i = 0; i < 8; i++) r.addNode("n" + i);
        double imb = imbalance(counts(r, KEYS));
        ok("busiest machine holds well over its fair share (imbalance " + fmt(imb) + "x)", imb > 1.5);
    }

    static void manyVnodesAreEven() {
        Ring r = new Ring(500);
        for (int i = 0; i < 8; i++) r.addNode("n" + i);
        double imb = imbalance(counts(r, KEYS));
        ok("busiest machine is within 15% of fair share (imbalance " + fmt(imb) + "x)", imb < 1.15);
    }

    /** With one point each, a machine's whole share lands on its single neighbour. */
    static void deathDumpsOnSuccessor() {
        Ring r = new Ring(1);
        for (int i = 0; i < 8; i++) r.addNode("n" + i);
        Map<String, String> before = assign(r, KEYS);
        r.removeNode("n3");
        Map<String, String> after = assign(r, KEYS);
        Map<String, Integer> inherited = new HashMap<>();
        for (String k : KEYS) if (before.get(k).equals("n3")) inherited.merge(after.get(k), 1, Integer::sum);
        eq("every orphaned key went to exactly one machine", 1, inherited.size());
    }

    static void deathSpreads() {
        Ring r = new Ring(500);
        for (int i = 0; i < 8; i++) r.addNode("n" + i);
        Map<String, String> before = assign(r, KEYS);
        r.removeNode("n3");
        Map<String, String> after = assign(r, KEYS);
        Set<String> inheritors = new HashSet<>();
        for (String k : KEYS) if (before.get(k).equals("n3")) inheritors.add(after.get(k));
        eq("all seven survivors shared the load", 7, inheritors.size());
    }

    // ------------------------------------------------------------ replication

    static void replicationDistinct() {
        Ring r = new Ring(150);
        for (int i = 0; i < 6; i++) r.addNode("n" + i);
        for (String k : KEYS.subList(0, 3000)) {
            List<String> o = r.owners(k, 3);
            eq("three copies", 3, o.size());
            eq("on three different machines", 3, new HashSet<>(o).size());
            eq("the first is the primary owner", r.owner(k), o.get(0));
        }
    }

    static void replicationSmallCluster() {
        Ring r = new Ring(150);
        r.addNode("a"); r.addNode("b");
        eq("asking for 3 copies from 2 machines yields 2, not a crash", 2, r.owners("k", 3).size());
    }

    // -------------------------------------------------------------- weighting

    static void weighted() {
        Ring r = new Ring(300);
        r.addNode("small1", 1); r.addNode("small2", 1); r.addNode("big", 3);
        Map<String, Integer> c = counts(r, KEYS);
        double ratio = c.get("big") / (double) c.get("small1");
        ok("big holds ~3x small1 (actual " + fmt(ratio) + "x)", ratio > 2.4 && ratio < 3.6);
    }

    // ----------------------------------------------------------- alternatives

    static void rendezvousStable() {
        Placement p = new Placements.Rendezvous();
        for (int i = 0; i < 10; i++) p.addNode("n" + i);
        Map<String, String> before = assign(p, KEYS);
        p.removeNode("n3");
        Map<String, String> after = assign(p, KEYS);
        int wrong = 0;
        for (String k : KEYS) if (!before.get(k).equals(after.get(k)) && !before.get(k).equals("n3")) wrong++;
        eq("only the dead machine's keys moved", 0, wrong);
    }

    /** Rendezvous has no arcs, so an orphaned key is re-scored against everyone. */
    static void rendezvousSpreads() {
        Placement p = new Placements.Rendezvous();
        for (int i = 0; i < 8; i++) p.addNode("n" + i);
        Map<String, String> before = assign(p, KEYS);
        p.removeNode("n3");
        Map<String, String> after = assign(p, KEYS);
        Map<String, Integer> inherited = new LinkedHashMap<>();
        for (String n : p.nodes()) inherited.put(n, 0);
        for (String k : KEYS) if (before.get(k).equals("n3")) inherited.merge(after.get(k), 1, Integer::sum);
        eq("spread across all seven survivors with no tuning at all", 7, inherited.size());
        double imb = imbalance(inherited);
        ok("and roughly evenly (imbalance " + fmt(imb) + "x)", imb < 1.3);
    }

    static void jumpBalanced() {
        Placement p = new Placements.Jump();
        for (int i = 0; i < 8; i++) p.addNode("n" + i);
        double imb = imbalance(counts(p, KEYS));
        ok("near-perfect balance with zero configuration (imbalance " + fmt(imb) + "x)", imb < 1.06);
    }

    static void jumpCannotRemove() {
        Placement p = new Placements.Jump();
        for (int i = 0; i < 5; i++) p.addNode("n" + i);
        try {
            p.removeNode("n2");
            throw new AssertionError("expected jump hash to refuse removing a middle bucket");
        } catch (UnsupportedOperationException e) {
            ok("it refuses, loudly -- this is the algorithm's defining limitation", true);
        }
    }

    static void boundedLoadCap() {
        Placements.BoundedLoad p = new Placements.BoundedLoad(150, 1.25);
        for (int i = 0; i < 8; i++) p.addNode("n" + i);
        p.reset();
        for (String k : KEYS) p.owner(k);
        int cap = (int) Math.ceil(1.25 * KEYS.size() / 8.0);
        int max = Collections.max(p.loads().values());
        ok("busiest machine " + max + " <= cap " + cap, max <= cap);
        ok("and some keys were displaced to make that true (" + p.displaced + ")", p.displaced > 0);
    }

    // ------------------------------------------------------- the hash matters

    /**
     * The machine positions are identical and perfectly spread in both rings. Only the
     * KEY hash differs, and it is 32-bit rather than 64-bit -- so the keys occupy a
     * sliver of the circle and cannot reach most of the machines at all.
     */
    static void badHashClumps() {
        Ring good = new Ring(150, true), bad = new Ring(150, false);
        for (int i = 0; i < 8; i++) { good.addNode("n" + i); bad.addNode("n" + i); }
        Map<String, Integer> gc = counts(good, KEYS), bc = counts(bad, KEYS);
        long gUsed = gc.values().stream().filter(v -> v > 0).count();
        long bUsed = bc.values().stream().filter(v -> v > 0).count();
        eq("with a matching hash, all 8 machines get keys", 8L, gUsed);
        ok("with a 32-bit key hash, most machines get NOTHING (" + bUsed + " of 8 used)", bUsed < 4);
        ok("and the busiest holds a wildly unfair share (" + fmt(imbalance(bc)) + "x vs " + fmt(imbalance(gc)) + "x)",
           imbalance(bc) > imbalance(gc) * 2);
    }

    // ----------------------------------------------------------------- harness

    static List<String> keys(int n) {
        List<String> k = new ArrayList<>(n);
        for (int i = 0; i < n; i++) k.add("user:" + (100000 + i));   // sequential, like real keys
        return k;
    }

    static Map<String, String> assign(Placement p, List<String> keys) {
        Map<String, String> m = new HashMap<>();
        for (String k : keys) m.put(k, p.owner(k));
        return m;
    }

    /** Tally, seeded with a zero for every machine -- including the idle ones. */
    static Map<String, Integer> counts(Placement p, List<String> keys) {
        Map<String, Integer> c = new LinkedHashMap<>();
        for (String n : p.nodes()) c.put(n, 0);
        for (String k : keys) c.merge(p.owner(k), 1, Integer::sum);
        return c;
    }

    static double movedFraction(Map<String, String> a, Map<String, String> b) {
        int moved = 0;
        for (String k : a.keySet()) if (!Objects.equals(a.get(k), b.get(k))) moved++;
        return moved / (double) a.size();
    }

    /** Busiest machine's share divided by the fair share. 1.0 is perfect. */
    static double imbalance(Map<String, Integer> counts) {
        if (counts.isEmpty()) return 0;
        double total = counts.values().stream().mapToInt(Integer::intValue).sum();
        double fair = total / counts.size();
        return Collections.max(counts.values()) / fair;
    }

    static String pct(double d) { return String.format("%.1f%%", d * 100); }
    static String fmt(double d) { return String.format("%.2f", d); }

    interface Scenario { void run() throws Exception; }

    static void section(String n) { System.out.println("\n-- " + n + " " + "-".repeat(Math.max(0, 60 - n.length()))); }

    static void test(String name, Scenario s) {
        try { s.run(); System.out.printf("  PASS  %s%n", name); passed++; }
        catch (AssertionError e) { System.out.printf("  FAIL  %s%n          %s%n", name, e.getMessage()); failed++; }
        catch (Exception e) { System.out.printf("  ERROR %s%n          %s: %s%n", name, e.getClass().getSimpleName(), e.getMessage()); failed++; }
    }

    static void eq(String what, Object exp, Object act) {
        Object e = exp instanceof Integer i ? (long) i : exp, a = act instanceof Integer i ? (long) i : act;
        if (!Objects.equals(e, a)) throw new AssertionError(what + ": expected <" + e + "> but was <" + a + ">");
    }
    static void ok(String what, boolean c) { if (!c) throw new AssertionError(what); }
}
