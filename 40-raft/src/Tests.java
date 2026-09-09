import java.util.*;

/**
 * Scenario tests that double as executable documentation. Every test name is a
 * sentence about Raft; if the sentence is true the test passes.
 *
 *   run:  java src/Tests.java
 */
public class Tests {
    static int passed = 0, failed = 0;
    static List<String> failures = new ArrayList<>();

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("  PASS  " + name); }
        else { failed++; failures.add(name + "  -- " + detail); System.out.println("  FAIL  " + name + "\n          " + detail); }
    }

    static void section(String s) { System.out.println("\n" + s + "\n" + "-".repeat(s.length())); }

    /** Bring a fresh cluster up and return its first leader. */
    static RaftNode elect(Cluster c) {
        c.runUntil(() -> c.leader() != null, 3000);
        return c.leader();
    }

    static int[] othersThan(Cluster c, int... exclude) {
        List<Integer> out = new ArrayList<>();
        outer:
        for (RaftNode n : c.nodes) {
            for (int e : exclude) if (n.id == e) continue outer;
            out.add(n.id);
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    static boolean allLogsIdentical(Cluster c) {
        String ref = null;
        for (RaftNode n : c.nodes) {
            if (!n.alive) continue;
            if (ref == null) ref = n.logString();
            else if (!ref.equals(n.logString())) return false;
        }
        return true;
    }

    public static void main(String[] args) {
        section("ELECTION");
        electionTests();
        section("LOG REPLICATION");
        replicationTests();
        section("CRASHES");
        crashTests();
        noOpLessonTest();
        section("NETWORK PARTITIONS");
        partitionTests();
        section("LOSSY AND HOSTILE NETWORKS");
        chaosTests();
        section("THE PLANTED BUGS (each switches off one rule)");
        bugTests();

        System.out.println("\n" + "=".repeat(64));
        System.out.printf("  %d passed, %d failed%n", passed, failed);
        for (String f : failures) System.out.println("   x " + f);
        System.out.println("=".repeat(64));
        if (failed > 0) System.exit(1);
    }

    // ============================================================ elections
    static void electionTests() {
        Cluster c = new Cluster(5, 1);
        RaftNode l = elect(c);
        check("a_leader_is_elected_from_a_cold_start", l != null,
                "no leader after 3000 ticks");
        check("the_winner_needed_a_majority_of_votes",
                l != null && l.votesReceived.size() >= 3,
                l == null ? "no leader" : "votes=" + l.votesReceived);
        check("the_first_election_happens_in_term_1", l != null && l.currentTerm == 1,
                l == null ? "no leader" : "term=" + l.currentTerm);

        c.run(2000);
        check("heartbeats_keep_the_term_stable_when_nothing_fails", c.maxTerm() == 1,
                "after 2000 quiet ticks the term is " + c.maxTerm() + " (should still be 1)");
        check("exactly_one_leader_exists_at_a_time", c.allLeaders().size() == 1,
                "leaders=" + c.allLeaders());
        check("no_safety_violation_in_a_quiet_cluster", c.violations.isEmpty(),
                String.valueOf(c.violations));

        // A candidate whose log is behind cannot win, whoever it asks.
        Cluster d = new Cluster(3, 7);
        RaftNode dl = elect(d);
        d.crash(2);                       // S2 will miss everything that follows
        d.submit("set x=1");
        d.runUntil(() -> d.node(1).applied.size() + d.node(0).applied.size() >= 2, 500);
        d.crash(dl.id);
        d.restart(2);
        d.letTimeout(2, 1);
        d.run(400);
        check("a_candidate_with_a_stale_log_cannot_win",
                d.node(2).role != RaftNode.Role.LEADER,
                "S2 has log " + d.node(2).logString() + " and became " + d.node(2).role);
    }

    // ======================================================== replication
    static void replicationTests() {
        Cluster c = new Cluster(5, 3);
        RaftNode l = elect(c);
        l.clientRequest("set x=1");
        c.runUntil(() -> c.committedCount("set x=1") == 5, 500);
        check("a_client_write_reaches_every_server", c.committedCount("set x=1") == 5,
                "only " + c.committedCount("set x=1") + "/5 applied it");

        l.clientRequest("set y=2");
        l.clientRequest("set x=3");
        c.runUntil(() -> c.committedCount("set x=3") == 5, 500);
        List<String> ref = c.node(0).applied;
        boolean same = true;
        for (RaftNode n : c.nodes) same &= n.applied.equals(ref);
        check("every_server_applies_the_same_commands_in_the_same_order", same,
                Arrays.toString(Arrays.stream(c.nodes).map(n -> n.applied.toString()).toArray()));
        check("the_order_is_the_leaders_order", ref.equals(List.of("set x=1", "set y=2", "set x=3")),
                "applied=" + ref);

        check("a_follower_refuses_client_writes", c.node((l.id + 1) % 5).clientRequest("nope") == -1,
                "a follower accepted a write");

        // Commit needs a majority, not everyone.
        Cluster d = new Cluster(5, 11);
        RaftNode dl = elect(d);
        d.crash(othersThan(d, dl.id)[0]);
        d.crash(othersThan(d, dl.id)[1]);
        dl.clientRequest("set z=9");
        d.runUntil(() -> dl.commitIndex >= 1, 500);
        check("a_write_commits_with_3_of_5_servers_even_though_2_are_dead", dl.commitIndex >= 1,
                "commitIndex=" + dl.commitIndex);

        // ...but not with a minority.
        Cluster e = new Cluster(5, 13);
        RaftNode el = elect(e);
        int[] rest = othersThan(e, el.id);
        e.crash(rest[0]); e.crash(rest[1]); e.crash(rest[2]);
        el.clientRequest("set q=1");
        e.run(600);
        check("a_write_does_not_commit_with_only_2_of_5_servers", el.commitIndex == 0,
                "commitIndex=" + el.commitIndex + " -- it committed on a minority!");
        check("the_uncommitted_write_is_still_sitting_in_the_leaders_log",
                el.logString().contains("set q=1"), el.logString());
    }

    /**
     * A subtlety worth its own test: a follower's leftover UNCOMMITTED entries are
     * not deleted the moment a new leader appears. They survive until that leader
     * appends an entry of its OWN. Real systems close this window by committing a
     * no-op entry immediately after winning an election.
     */
    static void noOpLessonTest() {
        Cluster c = new Cluster(5, 41);
        RaftNode l = elect(c);
        l.clientRequest("keep");
        c.runUntil(() -> c.committedCount("keep") == 5, 600);
        int[] rest = othersThan(c, l.id);
        int[] big = {rest[1], rest[2], rest[3]};
        c.partition(new int[]{l.id, rest[0]}, big);
        l.clientRequest("ghost-1");
        l.clientRequest("ghost-2");
        c.run(400);
        c.runUntil(() -> c.leaderAmong(big) != null, 3000);
        RaftNode neu = c.leaderAmong(big);
        c.heal();
        c.run(800);                                  // the new leader writes NOTHING at all
        check("an_uncommitted_entry_is_NOT_deleted_just_because_a_new_leader_appeared",
                l.logString().contains("ghost-1") && !neu.logString().contains("ghost-1"),
                "old leader log=" + l.logString() + "  new leader log=" + neu.logString());
        check("that_divergence_is_safe_because_nothing_was_ever_committed_there",
                c.violations.isEmpty() && l.commitIndex == 1, "violations=" + c.violations
                        + " old leader commitIndex=" + l.commitIndex);
        neu.clientRequest("real");
        c.runUntil(() -> allLogsIdentical(c), 2000);
        check("the_new_leaders_first_own_write_is_what_finally_deletes_it",
                allLogsIdentical(c) && !l.logString().contains("ghost-1"),
                "old leader log=" + l.logString() + "  new leader log=" + neu.logString());
    }

    // ============================================================= crashes
    static void crashTests() {
        Cluster c = new Cluster(5, 21);
        RaftNode l = elect(c);
        int oldTerm = l.currentTerm;
        l.clientRequest("set a=1");
        c.runUntil(() -> c.committedCount("set a=1") == 5, 500);

        c.crash(l.id);
        long t = c.runUntil(() -> c.leader() != null && c.leader().id != l.id, 3000);
        RaftNode l2 = c.leader();
        check("a_new_leader_appears_after_the_leader_crashes", l2 != null && l2.id != l.id,
                "leader is " + l2);
        check("the_new_leader_uses_a_higher_term", l2 != null && l2.currentTerm > oldTerm,
                "old term " + oldTerm + ", new term " + (l2 == null ? "-" : l2.currentTerm));
        check("the_new_leader_already_has_every_committed_entry",
                l2 != null && l2.logString().contains("set a=1"),
                l2 == null ? "no leader" : l2.logString());
        check("re_election_took_less_than_one_second_of_logical_time", t > 0 && t < 1000,
                "took " + t + " ticks");

        l2.clientRequest("set b=2");
        c.runUntil(() -> c.committedCount("set b=2") >= 3, 500);
        check("the_cluster_keeps_serving_writes_with_one_server_dead",
                c.committedCount("set b=2") >= 3, "applied on " + c.committedCount("set b=2") + " servers");

        c.restart(l.id);
        c.runUntil(() -> c.node(l.id).applied.size() == l2.applied.size(), 1500);
        check("the_restarted_server_catches_up_automatically",
                c.node(l.id).applied.equals(l2.applied),
                "S" + l.id + " applied " + c.node(l.id).applied + " vs leader " + l2.applied);
        check("no_safety_violation_across_a_crash_and_a_restart", c.violations.isEmpty(),
                String.valueOf(c.violations));

        // What a restart keeps is exactly what real Raft puts on disk.
        Cluster d = new Cluster(3, 31);
        RaftNode dl = elect(d);
        dl.clientRequest("durable");
        d.runUntil(() -> d.committedCount("durable") == 3, 500);
        RaftNode f = d.node((dl.id + 1) % 3);
        int termBefore = f.currentTerm, votedBefore = f.votedFor;
        String logBefore = f.logString();
        d.crash(f.id); d.restart(f.id);
        check("a_restart_keeps_term_votedFor_and_log_and_loses_the_rest",
                f.currentTerm == termBefore && f.votedFor == votedBefore
                        && f.logString().equals(logBefore) && f.commitIndex == 0,
                "term " + termBefore + "->" + f.currentTerm + ", votedFor " + votedBefore + "->" + f.votedFor
                        + ", log " + logBefore + "->" + f.logString() + ", commitIndex=" + f.commitIndex);
    }

    // ========================================================= partitions
    static void partitionTests() {
        Cluster c = new Cluster(5, 41);
        RaftNode l = elect(c);
        l.clientRequest("before");
        c.runUntil(() -> c.committedCount("before") == 5, 500);

        int[] rest = othersThan(c, l.id);
        int companion = rest[0];
        int[] majority = new int[]{rest[1], rest[2], rest[3]};
        c.partition(new int[]{l.id, companion}, majority);

        // The old leader is on the small side and does not know it yet.
        l.clientRequest("ghost-1");
        l.clientRequest("ghost-2");
        c.run(400);
        check("the_minority_side_cannot_commit_anything", l.commitIndex == 1,
                "old leader commitIndex=" + l.commitIndex + " (should still be 1)");
        check("the_minority_leader_still_believes_it_is_the_leader",
                l.role == RaftNode.Role.LEADER, "role=" + l.role);
        check("nobody_applied_the_minority_writes", c.committedCount("ghost-1") == 0,
                c.committedCount("ghost-1") + " servers applied ghost-1");

        c.runUntil(() -> c.leaderAmong(majority) != null, 3000);
        RaftNode l2 = c.leaderAmong(majority);
        check("the_majority_side_elects_its_own_leader", l2 != null, "no leader on the big side");
        check("there_are_now_two_leaders_but_in_different_terms",
                l2 != null && l2.currentTerm > l.currentTerm,
                "old term " + l.currentTerm + ", new term " + (l2 == null ? "-" : l2.currentTerm));

        l2.clientRequest("real-1");
        c.runUntil(() -> l2.commitIndex >= 2, 800);
        check("the_majority_side_keeps_committing_during_the_partition", l2.commitIndex >= 2,
                "majority leader commitIndex=" + l2.commitIndex);
        check("no_safety_violation_while_the_network_is_split", c.violations.isEmpty(),
                String.valueOf(c.violations));

        c.heal();
        c.runUntil(() -> l.role == RaftNode.Role.FOLLOWER, 1000);
        check("the_old_leader_steps_down_when_it_hears_the_higher_term",
                l.role == RaftNode.Role.FOLLOWER, "role=" + l.role + " term=" + l.currentTerm);

        c.runUntil(() -> allLogsIdentical(c), 3000);
        check("the_uncommitted_minority_writes_are_deleted",
                !l.logString().contains("ghost-1") && !l.logString().contains("ghost-2"),
                "old leader log = " + l.logString());
        check("every_log_converges_to_the_same_content", allLogsIdentical(c),
                Arrays.toString(Arrays.stream(c.nodes).map(RaftNode::logString).toArray()));
        check("the_write_made_before_the_partition_survived_intact",
                c.node(0).applied.get(0).equals("before"), "S0 applied " + c.node(0).applied);
        check("no_safety_violation_across_partition_and_heal", c.violations.isEmpty(),
                String.valueOf(c.violations));
    }

    // ============================================================== chaos
    static void chaosTests() {
        Cluster c = new Cluster(5, 51);
        c.net.dropRate = 0.30;
        RaftNode l = elect(c);
        check("a_leader_is_still_elected_with_30_percent_of_messages_dropped", l != null,
                "no leader after 3000 ticks");
        for (int i = 0; i < 5; i++) { if (c.leader() != null) c.leader().clientRequest("w" + i); c.run(200); }
        c.run(4000);
        check("writes_still_commit_with_30_percent_message_loss",
                c.node(0).applied.size() >= 3 || c.node(1).applied.size() >= 3,
                "applied sizes " + Arrays.toString(Arrays.stream(c.nodes).map(n -> n.applied.size()).toArray()));
        check("message_loss_never_broke_a_safety_property", c.violations.isEmpty(),
                String.valueOf(c.violations));

        Cluster d = new Cluster(5, 61);
        d.net.duplicateRate = 0.5;                  // every other message arrives twice
        RaftNode dl = elect(d);
        dl.clientRequest("only-once");
        d.runUntil(() -> d.committedCount("only-once") == 5, 800);
        int copies = 0;
        for (String s : d.node(0).applied) if (s.equals("only-once")) copies++;
        check("duplicated_messages_do_not_duplicate_the_write", copies == 1,
                "S0 applied \"only-once\" " + copies + " times");
        check("duplicates_never_broke_a_safety_property", d.violations.isEmpty(),
                String.valueOf(d.violations));

        // The long chaotic soak: random crashes, restarts, partitions and writes.
        Cluster e = new Cluster(5, 71);
        e.net.dropRate = 0.05;
        Random r = new Random(99);
        int writes = 0;
        for (int round = 0; round < 60; round++) {
            int dice = r.nextInt(10);
            if (dice < 2) { int v = r.nextInt(5); if (e.node(v).alive) e.crash(v); }
            else if (dice < 4) { int v = r.nextInt(5); if (!e.node(v).alive) e.restart(v); }
            else if (dice == 4) { e.partition(new int[]{0, 1}, new int[]{2, 3, 4}); }
            else if (dice == 5) { e.heal(); }
            else if (e.submit("chaos-" + writes) >= 0) writes++;
            e.run(150);
        }
        e.heal();
        for (RaftNode n : e.nodes) if (!n.alive) e.restart(n.id);
        e.run(5000);
        check("a_60_round_chaos_soak_produced_no_safety_violation", e.violations.isEmpty(),
                String.valueOf(e.violations));
        check("the_chaos_cluster_ends_with_one_leader_and_identical_logs",
                e.allLeaders().size() == 1 && allLogsIdentical(e),
                "leaders=" + e.allLeaders().size() + " logs="
                        + Arrays.toString(Arrays.stream(e.nodes).map(RaftNode::logString).toArray()));
    }

    // ======================================================== planted bugs
    static void bugTests() {
        // 1. Identical timeouts: safety survives, liveness does not.
        Bugs fixed = new Bugs(); fixed.fixedTimeout = true;
        Cluster a = new Cluster(5, 5, fixed);
        a.run(3000);
        check("bug_fixed_timeout_means_no_leader_is_ever_elected", a.leader() == null,
                "leader=" + a.leader());
        check("bug_fixed_timeout_costs_liveness_but_NOT_safety", a.violations.isEmpty(),
                String.valueOf(a.violations));
        check("bug_fixed_timeout_burns_through_terms_forever", a.maxTerm() > 10,
                "term only reached " + a.maxTerm());

        // 2. Same setup, but now servers may vote more than once per term.
        Bugs twice = new Bugs(); twice.fixedTimeout = true; twice.voteTwice = true;
        Cluster b = new Cluster(5, 5, twice);
        b.run(400);
        check("bug_vote_twice_creates_several_leaders_in_ONE_term", b.worstLeadersInOneTerm > 1,
                "worst simultaneous leaders in one term = " + b.worstLeadersInOneTerm);
        check("bug_vote_twice_is_caught_as_an_ELECTION_SAFETY_violation",
                b.violations.stream().anyMatch(v -> v.contains("ELECTION SAFETY")),
                String.valueOf(b.violations));

        // 3. Voting for a candidate whose log is behind erases a committed write.
        Bugs stale = new Bugs(); stale.noUpToDateCheck = true;
        Cluster ok = staleLeaderScenario(new Bugs());
        Cluster bad = staleLeaderScenario(stale);
        check("with_the_up_to_date_check_ON_the_committed_write_survives",
                ok.violations.isEmpty() && ok.node(1).logString().contains("committed-write"),
                "violations=" + ok.violations + " S1 log=" + ok.node(1).logString());
        check("bug_no_uptodate_check_ERASES_a_committed_write",
                !bad.violations.isEmpty(), "no violation detected; S1 log=" + bad.node(1).logString());

        // 4. Figure 8: committing an old-term entry by counting replicas.
        Cluster f8ok = figure8(new Bugs());
        Bugs anyTerm = new Bugs(); anyTerm.commitAnyTerm = true;
        Cluster f8bad = figure8(anyTerm);
        check("with_the_own_term_commit_rule_ON_figure_8_is_harmless", f8ok.violations.isEmpty(),
                String.valueOf(f8ok.violations));
        check("bug_commit_any_term_loses_an_entry_that_was_reported_committed",
                !f8bad.violations.isEmpty(), "no violation detected");

        // 5. Skipping the prefix check lets a follower silently miss entries.
        //    S2 is offline while a,b,c are written, so its log is EMPTY. A new
        //    leader assumes S2 is caught up and probes at index 3.
        Cluster gOk  = holeScenario(new Bugs());
        Bugs nomatch = new Bugs(); nomatch.noLogMatchCheck = true;
        Cluster gBad = holeScenario(nomatch);
        check("with_the_prefix_check_ON_the_empty_follower_is_repaired_from_scratch",
                gOk.node(2).logString().equals(gOk.node(1).logString()) && gOk.violations.isEmpty(),
                "S2 log=" + gOk.node(2).logString() + " leader log=" + gOk.node(1).logString()
                        + " violations=" + gOk.violations);
        check("bug_no_log_match_check_leaves_a_hole_in_the_followers_log",
                !gBad.node(2).logString().equals(gBad.node(1).logString()),
                "S2 log=" + gBad.node(2).logString() + " leader log=" + gBad.node(1).logString());
        check("bug_no_log_match_check_is_caught_as_a_LOG_MATCHING_violation",
                gBad.violations.stream().anyMatch(v -> v.contains("LOG MATCHING") || v.contains("DIVERGED")),
                String.valueOf(gBad.violations));
    }

    /**
     * S2 misses three writes entirely, then a new leader probes it at index 3.
     * The prevLogIndex/prevLogTerm check is the only thing that notices the hole.
     */
    static Cluster holeScenario(Bugs bugs) {
        Cluster c = new Cluster(5, 41, bugs);
        c.letTimeout(0, 1);
        c.runUntil(() -> c.node(0).role == RaftNode.Role.LEADER, 800);
        c.crash(2);                                    // S2 sleeps through everything
        c.node(0).clientRequest("a");
        c.node(0).clientRequest("b");
        c.node(0).clientRequest("c");
        c.runUntil(() -> c.node(0).commitIndex >= 3, 800);
        c.crash(0);
        c.run(80);
        c.letTimeout(1, 1);                            // S1 takes over with a 3-entry log
        c.runUntil(() -> c.node(1).role == RaftNode.Role.LEADER, 1500);
        c.restart(2);                                  // S2 wakes up with an EMPTY log
        c.run(400);
        if (c.node(1).role == RaftNode.Role.LEADER) c.node(1).clientRequest("d");
        c.run(800);
        return c;
    }

    /**
     * S0 leads and commits a write on {S0,S1}. S0 then dies and S2 -- which has an
     * empty log -- runs for leader. The up-to-date rule is the only thing standing
     * between S2 and the deletion of a committed write.
     */
    static Cluster staleLeaderScenario(Bugs bugs) {
        Cluster c = new Cluster(3, 7, bugs);
        c.letTimeout(0, 1);
        c.runUntil(() -> c.leader() != null, 800);
        c.crash(2);
        c.node(0).clientRequest("committed-write");
        c.runUntil(() -> c.node(0).commitIndex >= 1, 500);
        c.crash(0);
        c.run(60);                                 // let S0's last heartbeats drain out of the network
        c.restart(2);
        c.letTimeout(2, 1);
        c.runUntil(() -> c.leader() != null, 1500);
        if (c.leader() != null) c.leader().clientRequest("overwrite");  // a leader only truncates when it has something to send
        c.run(800);
        return c;
    }

    /**
     * The Raft paper's Figure 8, driven by hand. An entry from an old term reaches
     * a majority, and is STILL not safe -- because a server with a later-term entry
     * at the same index can still win an election and overwrite it.
     */
    static Cluster figure8(Bugs bugs) {
        Cluster c = new Cluster(5, 123, bugs);

        c.letTimeout(0, 1);                       // (a) S0 leads term 1
        c.runUntil(() -> c.node(0).role == RaftNode.Role.LEADER, 800);
        c.run(60);
        c.crash(2); c.crash(3); c.crash(4);
        c.node(0).clientRequest("OLD");           // reaches S0 and S1 only -> not committed
        c.run(200);

        c.crash(0); c.crash(1);                   // (b) S4 leads term 2 among S2,S3,S4
        c.restart(2); c.restart(3); c.restart(4);
        c.letTimeout(4, 1);
        c.runUntil(() -> c.node(4).role == RaftNode.Role.LEADER, 1500);
        c.crash(2); c.crash(3);
        c.node(4).clientRequest("NEW");           // reaches S4 only
        c.run(120);

        c.crash(4);                               // (c) S0 leads again and spreads OLD
        c.restart(0); c.restart(1); c.restart(2); c.restart(3);
        c.letTimeout(0, 1);
        c.runUntil(() -> c.node(0).role == RaftNode.Role.LEADER, 2000);
        c.run(400);                               // OLD now on S0,S1,S2,S3 = a majority

        c.crash(0);                               // (d) S4 comes back and wins on a fresher log
        c.run(80);                                // drain S0's last heartbeats, or they reset the timers we froze
        c.restart(4);
        c.letTimeout(4, 1);
        c.run(2500);
        return c;
    }
}
