import java.util.*;

/**
 * Executable documentation. Every test is named after a situation. Several
 * assert a FAILURE on purpose — the ones documenting what causal consistency
 * does NOT give you matter as much as the ones documenting what it does.
 *
 *   java src/Tests.java          all guarantees
 *   java src/Tests.java broken   three planted bugs in the delivery rule
 */
public class Tests {

    static int passed = 0, failed = 0;

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("broken")) { brokenSuite(); return; }

        section("The anomaly, and the rule that stops it");
        an_effect_can_arrive_before_its_cause();
        causal_delivery_holds_the_effect_until_the_cause_lands();
        the_held_write_becomes_visible_once_the_cause_arrives();
        a_replica_never_delays_its_own_clients_write();

        section("What 'causally after' actually means");
        reading_a_write_puts_it_in_your_causal_past();
        a_write_you_never_read_is_not_a_dependency();
        happens_before_is_transitive_across_three_clients();
        two_writes_neither_client_saw_are_concurrent();

        section("The four session guarantees");
        sticky_client_always_sees_its_own_writes();
        roaming_client_can_miss_its_own_write();
        causal_sticky_satisfies_all_four();

        section("What causal consistency does NOT give you");
        concurrent_writes_have_no_agreed_order();
        without_a_convergence_rule_replicas_stay_different();
        causal_plus_converges();
        a_causal_read_can_be_arbitrarily_stale();

        section("What it buys");
        a_partitioned_replica_keeps_answering();
        anti_entropy_is_what_repairs_a_partition_not_causal_delivery();

        section("The three encodings are interchangeable");
        full_deps_and_nearest_deps_give_the_same_visibility();
        vector_clock_over_orders_but_never_under_orders();
        lamport_order_is_causal_only_when_links_are_fifo();

        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------- the core anomaly

    /** Amira posts, Ben reads it and comments, Cara's replica gets the comment first. */
    static Cluster commentBeforePost(Rule rule) {
        Cluster c = new Cluster(3, rule, new Random(1));
        c.client("amira", 0); c.client("ben", 1); c.client("cara", 2);
        c.minDelay = 2; c.maxDelay = 2;
        c.linkDelay[0][2] = 10;                 // London -> Singapore is the slow route
        c.put("amira", "post", "photo");
        c.run(3);
        c.get("ben", "post");                   // Ben has now seen it
        c.put("ben", "comment", "nice");        // ...so the comment depends on the post
        c.run(3);
        return c;
    }

    static void an_effect_can_arrive_before_its_cause() {
        Cluster c = commentBeforePost(Rule.async());
        String comment = c.get("cara", "comment");
        String post = c.get("cara", "post");
        check("apply-on-arrival: Cara sees the comment", "nice".equals(comment));
        check("...while the post is still absent", post == null);
        check("...and the lab counts that as a causality anomaly", c.causalAnomalies == 1);
    }

    static void causal_delivery_holds_the_effect_until_the_cause_lands() {
        Cluster c = commentBeforePost(Rule.causalVv());
        check("causal delivery: Cara sees neither, not the wrong one", c.get("cara", "comment") == null);
        check("...the comment is received but deliberately held", c.pendingCount() == 1);
        check("...and no anomaly is observed", c.causalAnomalies == 0);
    }

    static void the_held_write_becomes_visible_once_the_cause_arrives() {
        Cluster c = commentBeforePost(Rule.causalVv());
        c.settle(50);
        check("once the post lands, the comment is released", "nice".equals(c.get("cara", "comment")));
        check("...and the post is there too", "photo".equals(c.get("cara", "post")));
        check("...nothing was left held", c.pendingCount() == 0);
    }

    static void a_replica_never_delays_its_own_clients_write() {
        Cluster c = new Cluster(3, Rule.causalVv(), new Random(2));
        c.client("amira", 0);
        c.minDelay = 50; c.maxDelay = 50;              // the rest of the world is unreachable
        c.put("amira", "x", "1");
        check("your own write is visible to you immediately", "1".equals(c.get("amira", "x")));
    }

    // ----------------------------------------------------- what depends on what

    static void reading_a_write_puts_it_in_your_causal_past() {
        Cluster c = new Cluster(3, Rule.causalVv(), new Random(3));
        c.client("amira", 0); c.client("ben", 1);
        c.minDelay = 1; c.maxDelay = 1;
        Write post = c.put("amira", "post", "photo");
        c.settle(10);
        c.get("ben", "post");
        Write comment = c.put("ben", "comment", "nice");
        check("Ben read the post, so his comment depends on it", comment.deps().contains(post.id()));
    }

    static void a_write_you_never_read_is_not_a_dependency() {
        Cluster c = new Cluster(3, Rule.causalVv(), new Random(4));
        c.client("amira", 0); c.client("ben", 1);
        c.minDelay = 1; c.maxDelay = 1;
        Write post = c.put("amira", "post", "photo");
        c.settle(10);
        Write other = c.put("ben", "comment", "unrelated");   // Ben never read the post
        check("a write Ben never read is NOT in his write's dependencies",
              !other.deps().contains(post.id()));
    }

    static void happens_before_is_transitive_across_three_clients() {
        Cluster c = new Cluster(3, Rule.causalVv(), new Random(5));
        c.client("amira", 0); c.client("ben", 1); c.client("cara", 2);
        c.minDelay = 1; c.maxDelay = 1;
        Write a = c.put("amira", "a", "1");
        c.settle(10);
        c.get("ben", "a");
        Write b = c.put("ben", "b", "2");
        c.settle(10);
        c.get("cara", "b");
        Write d = c.put("cara", "c", "3");
        check("A -> B and B -> C implies A -> C", d.deps().contains(a.id()) && d.deps().contains(b.id()));
    }

    static void two_writes_neither_client_saw_are_concurrent() {
        Cluster c = new Cluster(3, Rule.causalVv(), new Random(6));
        c.client("amira", 0); c.client("ben", 1);
        c.minDelay = 5; c.maxDelay = 5;
        Write a = c.put("amira", "title", "Dog Photos");
        Write b = c.put("ben", "title", "My Dog");
        check("neither write is in the other's past — they are concurrent",
              !a.deps().contains(b.id()) && !b.deps().contains(a.id()));
    }

    // --------------------------------------------------- session guarantees

    static void sticky_client_always_sees_its_own_writes() {
        int misses = 0;
        for (int s = 0; s < 200; s++) {
            Cluster c = new Cluster(3, Rule.causalVv(), new Random(s));
            c.client("amira", 0);
            c.minDelay = 3; c.maxDelay = 5;
            c.put("amira", "profile", "new");
            c.step();
            if (!"new".equals(c.get("amira", "profile"))) misses++;
        }
        check("200/200 sticky reads see the client's own write (" + (200 - misses) + "/200)", misses == 0);
    }

    static void roaming_client_can_miss_its_own_write() {
        int misses = 0;
        for (int s = 0; s < 200; s++) {
            Cluster c = new Cluster(3, Rule.causalRoaming(), new Random(s));
            c.client("amira", 0);
            c.minDelay = 3; c.maxDelay = 5;
            c.put("amira", "profile", "new");
            c.step();
            if (!"new".equals(c.get("amira", "profile"))) misses++;
        }
        check("a roaming client misses its own write sometimes (" + misses + "/200)", misses > 0);
    }

    static void causal_sticky_satisfies_all_four() {
        int ryw = 0, mr = 0, mw = 0, wfr = 0;
        for (int s = 0; s < 100; s++) {
            Cluster c = Experiments.workload(Rule.causalVv(), s, 40, 12);
            ryw += c.readYourWrites; mr += c.monotonicReads;
            mw += c.monotonicWrites; wfr += c.writesFollowReads;
        }
        check("causal + sticky: read-your-writes holds", ryw == 0);
        check("causal + sticky: monotonic reads holds", mr == 0);
        check("causal + sticky: monotonic writes holds", mw == 0);
        check("causal + sticky: writes-follow-reads holds", wfr == 0);
    }

    // --------------------------------------------------- the explicit limits

    static void concurrent_writes_have_no_agreed_order() {
        Cluster c = new Cluster(3, Rule.causalVv(), new Random(7));
        c.client("amira", 0); c.client("ben", 1);
        c.minDelay = 3; c.maxDelay = 3;
        Write a = c.put("amira", "title", "A");
        Write b = c.put("ben", "title", "B");
        boolean neitherOrdered = !a.deps().contains(b.id()) && !b.deps().contains(a.id());
        check("causality has no opinion about two concurrent writes", neitherOrdered);
    }

    static void without_a_convergence_rule_replicas_stay_different() {
        int diverged = 0;
        for (int s = 0; s < 100; s++) {
            Cluster c = new Cluster(3, Rule.causalNoConverge(), new Random(s));
            c.client("amira", 0); c.client("ben", 1);
            c.minDelay = 1; c.maxDelay = 5;
            c.put("amira", "title", "A");
            c.put("ben", "title", "B");
            c.settle(40);
            if (!c.converged()) diverged++;
        }
        check("causal ordering alone leaves replicas disagreeing (" + diverged + "/100)", diverged > 0);
    }

    static void causal_plus_converges() {
        int diverged = 0;
        for (int s = 0; s < 100; s++) {
            Cluster c = new Cluster(3, Rule.causalVv(), new Random(s));
            c.client("amira", 0); c.client("ben", 1);
            c.minDelay = 1; c.maxDelay = 5;
            c.put("amira", "title", "A");
            c.put("ben", "title", "B");
            c.settle(40);
            if (!c.converged()) diverged++;
        }
        check("causal+ (one agreed tie-break) converges 100/100", diverged == 0);
    }

    static void a_causal_read_can_be_arbitrarily_stale() {
        Cluster c = new Cluster(3, Rule.causalVv(), new Random(8));
        c.client("amira", 0); c.client("cara", 2);
        c.minDelay = 1; c.maxDelay = 1;
        c.put("amira", "x", "old");
        c.settle(10);
        c.partition(0, 0); c.partition(1, 0); c.partition(2, 1);
        c.put("amira", "x", "new");
        c.run(100);
        check("a legal causal read returns a value 100 ticks out of date",
              "old".equals(c.get("cara", "x")) && c.causalAnomalies == 0);
    }

    // ------------------------------------------------------------ what it buys

    static void a_partitioned_replica_keeps_answering() {
        Cluster c = new Cluster(3, Rule.causalVv(), new Random(9));
        c.client("cara", 2);
        c.minDelay = 1; c.maxDelay = 1;
        c.partition(0, 0); c.partition(1, 0); c.partition(2, 1);
        int answered = 0;
        for (int i = 0; i < 100; i++) {
            c.put("cara", "note", "n" + i);
            if (("n" + i).equals(c.get("cara", "note"))) answered++;
            c.step();
        }
        check("the isolated minority answers all 100 operations", answered == 100);
    }

    static void anti_entropy_is_what_repairs_a_partition_not_causal_delivery() {
        Cluster c = new Cluster(3, Rule.causalVv(), new Random(10));
        c.client("amira", 0); c.client("cara", 2);
        c.minDelay = 1; c.maxDelay = 1;
        c.partition(0, 0); c.partition(1, 0); c.partition(2, 1);
        c.put("amira", "x", "written-during-outage");
        c.heal();
        c.settle(30);
        check("healing alone does NOT deliver what was lost", c.get("cara", "x") == null);
        c.antiEntropy();
        c.settle(30);
        check("an anti-entropy round does", "written-during-outage".equals(c.get("cara", "x")));
    }

    // --------------------------------------------------- the three encodings

    static void full_deps_and_nearest_deps_give_the_same_visibility() {
        boolean same = true;
        for (int s = 0; s < 100; s++) {
            Cluster a = Experiments.workload(Rule.causalDeps(), s, 30, 8);
            Cluster b = Experiments.workload(Rule.causalNear(), s, 30, 8);
            a.settle(60); b.settle(60);
            for (int r = 0; r < 3; r++) if (!a.view(r).equals(b.view(r))) same = false;
        }
        check("nearest-only dependencies give exactly the same result as full ones", same);
    }

    static void vector_clock_over_orders_but_never_under_orders() {
        int anomalies = 0, extraHeld = 0;
        for (int s = 0; s < 100; s++) {
            Cluster deps = Experiments.workload(Rule.causalDeps(), s, 40, 12);
            Cluster vv = Experiments.workload(Rule.causalVv(), s, 40, 12);
            anomalies += vv.causalAnomalies;
            if (vv.totalHoldTicks > deps.totalHoldTicks) extraHeld++;
        }
        check("a per-replica counter never shows an effect before its cause", anomalies == 0);
        check("...but it does make writes wait longer than naming the deps ("
              + extraHeld + "/100 runs)", extraHeld > 0);
    }

    static void lamport_order_is_causal_only_when_links_are_fifo() {
        int reorderAnomalies = 0, fifoAnomalies = 0;
        for (int s = 0; s < 300; s++) {
            reorderAnomalies += Experiments.workload(Rule.lamport(), s, 40, 12, false).causalAnomalies;
            fifoAnomalies    += Experiments.workload(Rule.lamport(), s, 40, 12, true).causalAnomalies;
        }
        check("Lamport order leaks when messages can overtake on a link ("
              + reorderAnomalies + " anomalies)", reorderAnomalies > 0);
        check("Lamport order is causal once each link is FIFO ("
              + fifoAnomalies + " anomalies)", fifoAnomalies == 0);
    }

    // ------------------------------------------------------------------ broken

    static void brokenSuite() {
        System.out.println("Three plausible, wrong versions of the delivery rule.\n");
        for (String bug : List.of("same-replica-only", "same-key-only", "vv-no-gap-check")) {
            int hit = 0, total = 0;
            for (int s = 0; s < 200; s++) {
                Cluster c = brokenWorkload(bug, s);
                if (c.causalAnomalies > 0) hit++;
                total += c.causalAnomalies;
            }
            System.out.printf("  %-20s  anomalies in %3d/200 runs, %d total%n", bug, hit, total);
        }
        System.out.println();
        // the deterministic scenario, so each bug can be seen rather than counted
        for (String bug : List.of("same-replica-only", "same-key-only", "vv-no-gap-check")) {
            Cluster c = new Cluster(3, Rule.causalDeps(), new Random(1));
            c.bug = bug;
            c.client("amira", 0); c.client("ben", 1); c.client("cara", 2);
            c.minDelay = 2; c.maxDelay = 2; c.linkDelay[0][2] = 10;
            c.put("amira", "post", "photo");
            c.run(3);
            c.get("ben", "post");
            c.put("ben", "comment", "nice");
            c.run(3);
            String comment = c.get("cara", "comment");
            boolean reproduced = "nice".equals(comment) && c.get("cara", "post") == null;
            System.out.printf("  %-20s  comment-before-post reproduced: %s%n", bug,
                    reproduced ? "YES" : "no  (this bug needs a gap in one author's own sequence,\n"
                                       + "                                                        which this two-write story never creates)");
        }
        System.out.println("\n  Bug 'same-key-only' is the one to remember: it is exactly the guarantee\n"
                         + "  a per-object version vector gives you, and people reach for it believing\n"
                         + "  it is causal consistency. Causality is a statement ACROSS keys.");
    }

    static Cluster brokenWorkload(String bug, int seed) {
        Random rnd = new Random(seed);
        Cluster c = new Cluster(3, Rule.causalDeps(), rnd);
        c.bug = bug;
        c.client("amira", 0); c.client("ben", 1); c.client("cara", 2);
        c.minDelay = 1; c.maxDelay = 3; c.linkDelay[0][2] = 12;
        String[] names = {"amira", "ben", "cara"};
        String[] keys = {"post", "comment", "like", "profile"};
        for (int i = 0; i < 40; i++) {
            String who = names[rnd.nextInt(names.length)];
            String key = keys[rnd.nextInt(keys.length)];
            if (rnd.nextInt(3) == 0) c.get(who, key);
            else { c.get(who, keys[rnd.nextInt(keys.length)]); c.put(who, key, key + "-v" + i); }
            c.step();
        }
        c.run(6);
        return c;
    }

    // ------------------------------------------------------------------ utils

    static void section(String s) { System.out.println("\n== " + s); }

    static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("  PASS  " + name); }
        else    { failed++; System.out.println("  FAIL  " + name); }
    }
}
