import java.util.*;

/**
 * Executable documentation. Every test is named after a sentence somebody could
 * say about a real cluster, and asserts the guarantee the README claims for it.
 *
 * Run:          java src/Tests.java
 * Exercise 6:   java src/Tests.java broken      (swaps in the planted-bug merge)
 */
public class Tests {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.printf("  PASS  %-54s %s%n", name, detail); }
        else    { failed++; System.out.printf("  FAIL  %-54s %s%n", name, detail); }
    }

    public static void main(String[] args) {
        Node.brokenMerge = args.length > 0 && args[0].equals("broken");
        System.out.println("\nRUNNING TESTS" + (Node.brokenMerge ? "  [BrokenMerge - exercise 6]" : "") + "\n");

        merging_the_same_news_twice_changes_nothing();
        merging_in_either_order_gives_the_same_answer();
        a_higher_incarnation_always_wins();
        bad_news_beats_good_news_at_the_same_incarnation();
        a_fact_published_at_one_node_reaches_every_node();
        a_fact_still_reaches_everyone_with_half_the_messages_lost();
        a_crashed_node_is_eventually_declared_dead_by_everyone();
        a_node_that_was_only_slow_refutes_its_own_death_and_stays_alive();
        a_refuted_node_is_not_re_suspected_by_stale_gossip();
        two_halves_of_a_partition_reconcile_when_it_heals();
        no_node_ever_believes_itself_dead();
        nobody_keeps_gossiping_to_a_node_declared_dead();

        System.out.printf("%n%d passed, %d failed%n%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------- the merge rule

    static PeerState st(long inc, long hb, Status s) { return new PeerState(inc, hb, s, 0); }

    static void merging_the_same_news_twice_changes_nothing() {
        PeerState mine = st(1, 5, Status.ALIVE);
        PeerState in = st(1, 7, Status.SUSPECT);
        boolean first = Node.brokenMerge ? BrokenMerge.mergeFrom(mine, in, 1) : mine.mergeFrom(in, 1);
        boolean second = Node.brokenMerge ? BrokenMerge.mergeFrom(mine, in, 2) : mine.mergeFrom(in, 2);
        check("merging_the_same_news_twice_changes_nothing", first && !second,
                "first merge changed something=" + first + ", replay changed something=" + second);
    }

    /** Gossip has no ordering. If order mattered, nodes would never agree. */
    static void merging_in_either_order_gives_the_same_answer() {
        List<PeerState> news = List.of(
                st(1, 3, Status.ALIVE), st(1, 9, Status.ALIVE),
                st(1, 6, Status.SUSPECT), st(2, 4, Status.ALIVE), st(1, 12, Status.DEAD));
        Set<String> outcomes = new HashSet<>();
        List<PeerState> perm = new ArrayList<>(news);
        for (int i = 0; i < 40; i++) {
            Collections.shuffle(perm, new Random(i));
            PeerState acc = st(1, 0, Status.ALIVE);
            for (PeerState p : perm) {
                PeerState copy = p.copy();
                if (Node.brokenMerge) BrokenMerge.mergeFrom(acc, copy, 1); else acc.mergeFrom(copy, 1);
            }
            outcomes.add(acc.status + "/" + acc.incarnation + "/" + acc.heartbeat);
        }
        check("merging_in_either_order_gives_the_same_answer", outcomes.size() == 1,
                outcomes.size() + " distinct results from 40 orderings: " + outcomes);
    }

    static void a_higher_incarnation_always_wins() {
        PeerState mine = st(3, 50, Status.DEAD);
        PeerState refutation = st(4, 51, Status.ALIVE);
        if (Node.brokenMerge) BrokenMerge.mergeFrom(mine, refutation, 1); else mine.mergeFrom(refutation, 1);
        check("a_higher_incarnation_always_wins", mine.status == Status.ALIVE && mine.incarnation == 4,
                "DEAD(i3) + ALIVE(i4) = " + mine);
    }

    static void bad_news_beats_good_news_at_the_same_incarnation() {
        PeerState mine = st(2, 10, Status.ALIVE);
        if (Node.brokenMerge) BrokenMerge.mergeFrom(mine, st(2, 4, Status.SUSPECT), 1);
        else mine.mergeFrom(st(2, 4, Status.SUSPECT), 1);
        check("bad_news_beats_good_news_at_the_same_incarnation", mine.status == Status.SUSPECT,
                "ALIVE(i2,hb10) + SUSPECT(i2,hb4) = " + mine);
    }

    // --------------------------------------------------------- dissemination

    static void a_fact_published_at_one_node_reaches_every_node() {
        Cluster c = new Cluster(200, 7, false);
        c.fanout = 2; c.suspectAfter = 10_000;
        c.publishConfig("n1", "leader=n9", 2);
        int r = 0;
        while (c.nodesWith(2) < 200 && r < 50) { c.runRound(); r++; }
        check("a_fact_published_at_one_node_reaches_every_node", c.nodesWith(2) == 200,
                "200 nodes, fanout 2, converged in " + r + " rounds");
    }

    static void a_fact_still_reaches_everyone_with_half_the_messages_lost() {
        Cluster c = new Cluster(200, 11, false);
        c.fanout = 2; c.suspectAfter = 10_000; c.dropRate = 0.5;
        c.publishConfig("n1", "leader=n9", 2);
        int r = 0;
        while (c.nodesWith(2) < 200 && r < 80) { c.runRound(); r++; }
        check("a_fact_still_reaches_everyone_with_half_the_messages_lost", c.nodesWith(2) == 200,
                r + " rounds, " + c.messagesDropped + " messages lost, no retries anywhere");
    }

    // ------------------------------------------------------------ detection

    static void a_crashed_node_is_eventually_declared_dead_by_everyone() {
        Cluster c = new Cluster(30, 3);
        c.fanout = 3; c.suspectAfter = 3; c.deadAfter = 3;
        for (int i = 0; i < 5; i++) c.runRound();
        c.kill("n9");
        int r = 0;
        while (c.believing("n9", Status.DEAD) < c.runningCount() && r < 60) { c.runRound(); r++; }
        check("a_crashed_node_is_eventually_declared_dead_by_everyone",
                c.believing("n9", Status.DEAD) == c.runningCount(),
                c.believing("n9", Status.DEAD) + "/" + c.runningCount() + " nodes agreed after " + r + " rounds");
    }

    /** The one that separates a working membership protocol from a flapping one. */
    static void a_node_that_was_only_slow_refutes_its_own_death_and_stays_alive() {
        Cluster c = new Cluster(20, 5);
        c.fanout = 3; c.suspectAfter = 2; c.deadAfter = 100;
        for (int i = 0; i < 4; i++) c.runRound();
        c.kill("n4");                                    // a long pause, not a crash
        for (int i = 0; i < 5; i++) c.runRound();
        long suspected = c.believing("n4", Status.SUSPECT);
        c.revive("n4");
        for (int i = 0; i < 6; i++) c.runRound();
        check("a_node_that_was_only_slow_refutes_its_own_death_and_stays_alive",
                suspected > 0 && c.believing("n4", Status.ALIVE) == c.runningCount(),
                suspected + " nodes suspected it; after it spoke up, "
                        + c.believing("n4", Status.ALIVE) + "/" + c.runningCount()
                        + " say ALIVE (incarnation " + c.byId.get("n4").incarnation + ")");
    }

    /** A refutation must also survive the old rumour still circulating behind it. */
    static void a_refuted_node_is_not_re_suspected_by_stale_gossip() {
        Node n = new Node("nX");
        n.view.put("nY", st(1, 10, Status.ALIVE));
        // nY refutes a suspicion: incarnation 2, alive.
        PeerState refutation = st(2, 11, Status.ALIVE);
        if (Node.brokenMerge) BrokenMerge.mergeFrom(n.view.get("nY"), refutation, 5);
        else n.view.get("nY").mergeFrom(refutation, 5);
        // ...and now a stale copy of the old suspicion arrives, late, with a big heartbeat.
        PeerState stale = st(1, 99, Status.SUSPECT);
        if (Node.brokenMerge) BrokenMerge.mergeFrom(n.view.get("nY"), stale, 6);
        else n.view.get("nY").mergeFrom(stale, 6);
        check("a_refuted_node_is_not_re_suspected_by_stale_gossip",
                n.view.get("nY").status == Status.ALIVE,
                "after refutation(i2) then stale SUSPECT(i1,hb99): " + n.view.get("nY"));
    }

    // ------------------------------------------------------------ partitions

    static void two_halves_of_a_partition_reconcile_when_it_heals() {
        Cluster c = new Cluster(40, 13);
        c.fanout = 3; c.suspectAfter = 10_000;
        List<String> right = new ArrayList<>();
        for (int i = 21; i <= 40; i++) right.add("n" + i);
        c.partition(right);
        c.publishConfig("n1", "left-side-fact", 2);
        for (int i = 0; i < 10; i++) c.runRound();
        long duringSplit = c.nodesWith(2);
        c.heal();
        for (int i = 0; i < 10; i++) c.runRound();
        check("two_halves_of_a_partition_reconcile_when_it_heals",
                duringSplit == 20 && c.nodesWith(2) == 40,
                duringSplit + "/40 during the split, " + c.nodesWith(2) + "/40 after healing");
    }

    static void no_node_ever_believes_itself_dead() {
        Cluster c = new Cluster(25, 17);
        c.fanout = 3; c.suspectAfter = 2; c.deadAfter = 2;
        for (int i = 0; i < 3; i++) c.runRound();
        c.kill("n5"); c.kill("n6");
        for (int i = 0; i < 8; i++) c.runRound();
        c.revive("n5"); c.revive("n6");
        for (int i = 0; i < 10; i++) c.runRound();
        boolean ok = c.nodes.stream().filter(n -> n.running)
                .allMatch(n -> n.believesAbout(n.id) == Status.ALIVE);
        check("no_node_ever_believes_itself_dead", ok,
                "every running node still lists itself as ALIVE after being written off and returning");
    }

    static void nobody_keeps_gossiping_to_a_node_declared_dead() {
        Cluster c = new Cluster(20, 19);
        c.fanout = 3; c.suspectAfter = 2; c.deadAfter = 2;
        for (int i = 0; i < 3; i++) c.runRound();
        c.kill("n8");
        for (int i = 0; i < 20; i++) c.runRound();
        long stillTargeting = c.nodes.stream().filter(n -> n.running)
                .filter(n -> n.gossipTargets().contains("n8")).count();
        long before = c.messagesSent;
        for (int i = 0; i < 5; i++) c.runRound();
        long perRound = (c.messagesSent - before) / 5;
        long allAmongTheLiving = 19 * 3 * 2;      // 19 survivors x fanout 3 x (push + reply)
        check("nobody_keeps_gossiping_to_a_node_declared_dead",
                c.believing("n8", Status.DEAD) == c.runningCount() && stillTargeting == 0
                        && perRound == allAmongTheLiving,
                stillTargeting + " nodes still list n8 as a partner; " + perRound
                        + " messages/round, all of them between the 19 living nodes");
    }
}
