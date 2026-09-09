import java.util.*;

/** Scenario checks that double as documentation. Run: java src/Tests.java */
public class Tests {
    static int pass = 0, fail = 0;

    static void check(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  PASS  " + name); }
        else      { fail++; System.out.println("  FAIL  " + name); }
    }

    static boolean lin(History h) { return Checker.check(h, Checker.Opts.linearizable()).ok; }
    static boolean seq(History h) { return Checker.check(h, Checker.Opts.sequential()).ok; }

    public static void main(String[] args) {
        System.out.println("\n=== 1. the checker on hand-built histories ===\n");
        check("write_then_read_is_linearizable",                     lin(Scenarios.ok()));
        check("stale_read_is_not_linearizable",                      !lin(Scenarios.staleRead()));
        check("stale_read_IS_sequentially_consistent",               seq(Scenarios.staleRead()));
        check("overlapping_write_read_before_and_after_is_ok",       lin(Scenarios.concurrentOk()));
        check("two_readers_disagreeing_is_not_linearizable",         !lin(Scenarios.twoReadersDisagree()));
        check("two_readers_disagreeing_IS_sequentially_consistent",  seq(Scenarios.twoReadersDisagree()));
        check("same_client_flip_flop_is_not_linearizable",           !lin(Scenarios.flipFlop()));
        check("same_client_flip_flop_is_not_even_sequential",        !seq(Scenarios.flipFlop()));
        check("timed_out_write_that_is_seen_is_linearizable",        lin(Scenarios.pendingSeen()));
        check("timed_out_write_that_flickers_is_not",                !lin(Scenarios.pendingFlickers()));
        check("lost_update_history_is_still_linearizable",           lin(Scenarios.lostUpdate()));
        check("compare_and_set_history_is_linearizable",             lin(Scenarios.casProtects()));

        System.out.println("\n=== 2. what the real-time rule is actually doing ===\n");
        // Identical answers, only the clock differs: overlap makes it legal, separation makes it a bug.
        History overlapping = History.of("overlapping", Op.put(1, 1, 0, 20), Op.get(2, 0, 5, 10));
        History separated   = History.of("separated",   Op.put(1, 1, 0, 4),  Op.get(2, 0, 5, 10));
        check("same_answers_overlapping_in_time_are_legal",          lin(overlapping));
        check("same_answers_separated_in_time_are_illegal",          !lin(separated));
        check("sequential_checker_cannot_tell_them_apart",           seq(overlapping) && seq(separated));

        System.out.println("\n=== 3. reading a value nobody ever wrote ===\n");
        check("read_of_unwritten_value_is_never_legal",              !lin(History.of("ghost", Op.get(1, 7, 0, 4))));
        check("cas_that_claims_success_without_the_value",           !lin(History.of("bad cas", Op.cas(1, 5, 6, true, 0, 4))));
        check("cas_failure_from_initial_state_is_fine",              lin(History.of("ok cas", Op.cas(1, 5, 6, false, 0, 4))));

        System.out.println("\n=== 4. the planted bug: a checker that ignores timed-out writes ===\n");
        Checker.Opts dropped = Checker.Opts.linearizable(); dropped.dropPending = true;
        check("good_checker_accepts_the_timed_out_write",            lin(Scenarios.pendingSeen()));
        check("buggy_checker_raises_a_false_alarm",                  !Checker.check(Scenarios.pendingSeen(), dropped).ok);

        System.out.println("\n=== 5. locality: linearizability composes, sequential consistency does not ===\n");
        History two = Scenarios.twoObjects();
        check("object_x_alone_is_sequentially_consistent",           seq(two.project("x")));
        check("object_y_alone_is_sequentially_consistent",           seq(two.project("y")));
        check("but_the_two_together_are_NOT",                        !seq(two));
        check("object_x_alone_is_not_linearizable_though",           !lin(two.project("x")));
        check("merging_two_linearizable_objects_stays_linearizable",  mergeStaysLinearizable(150));

        System.out.println("\n=== 6. real designs, judged by the checker ===\n");
        check("single_copy_store_is_linearizable",                   allLin(Sim.Mode.SINGLE, 1, 1, 1, 100));
        check("leader_reads_are_linearizable",                       allLin(Sim.Mode.LEADER, 3, 1, 1, 100));
        check("async_replica_reads_are_not",                         someBroken(Sim.Mode.ASYNC, 3, 1, 1, 100));
        check("quorum_R2_W2_N3_without_write_back_is_not",           someBroken(Sim.Mode.QUORUM_NAIVE, 3, 2, 2, 100));
        check("quorum_R2_W2_N3_with_write_back_is",                  allLin(Sim.Mode.QUORUM_ABD, 3, 2, 2, 300));
        check("clock_stamped_writes_break_it_again",                 someBrokenSkew(Sim.Mode.QUORUM_CLOCK, 3, 2, 2, 100));
        check("a_cache_in_front_of_it_breaks_it",                    someBroken(Sim.Mode.CACHED, 3, 2, 2, 100));
        check("naive_quorum_looks_perfect_on_a_calm_network",        calmIsClean(Sim.Mode.QUORUM_NAIVE, 300));
        check("write_back_holds_with_four_concurrent_writers",       manyWritersOk(300));
        check("dropping_the_writer_tiebreak_breaks_it",              tiebreakMatters(300));
        // Skipping the write-back when the read quorum already agreed is safe only
        // when two read quorums must intersect each other: 2R > N.
        check("lazy_write_back_is_safe_when_2R_over_N",               lazyOk(3, 2, 2, 300));
        check("lazy_write_back_is_unsafe_when_2R_not_over_N",         !lazyOk(5, 2, 4, 300));

        System.out.println("\n=== 7. shrinking a violation to something you can read ===\n");
        Sim s = new Sim(Sim.Mode.QUORUM_NAIVE, 3, 2, 2, 7);
        History bad = firstBroken(s, 4, 5, 400);
        check("a_violation_was_found_to_shrink",                     bad != null);
        if (bad != null) {
            History small = Checker.minimize(bad, Checker.Opts.linearizable());
            check("the_shrunk_history_is_still_broken",              !lin(small));
            check("the_shrunk_history_is_at_most_4_operations",      small.size() <= 4);
            System.out.println(small.render());
        }

        System.out.println("\n=== 8. the memo table changes cost, never verdicts ===\n");
        Checker.Opts noMemo = Checker.Opts.linearizable(); noMemo.memo = false;
        Sim s2 = rig(Sim.Mode.QUORUM_ABD, 3, 2, 2, 11);
        History h = s2.run(4, 5, 0);
        var withMemo = Checker.check(h, Checker.Opts.linearizable());
        var without  = Checker.check(h, noMemo);
        check("same_verdict_with_and_without_memoisation",           withMemo.ok == without.ok);
        System.out.println("  states explored: with memo " + withMemo.nodes + ", without " + without.nodes);

        System.out.printf("%n%d passed, %d failed%n%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    /** The standard lab setup: one writer, three readers, a network with occasional hiccups. */
    static Sim rig(Sim.Mode m, int n, int r, int w, long seed) {
        Sim s = new Sim(m, n, r, w, seed);
        s.jitterProb = 0.15; s.writers = 1;
        return s;
    }

    static boolean allLin(Sim.Mode m, int n, int r, int w, int rounds) {
        for (int i = 0; i < rounds; i++)
            if (!lin(rig(m, n, r, w, i).run(4, 5, i))) { System.out.println("        (round " + i + " was not linearizable)"); return false; }
        return true;
    }

    static boolean someBroken(Sim.Mode m, int n, int r, int w, int rounds) {
        for (int i = 0; i < rounds; i++) if (!lin(rig(m, n, r, w, i).run(4, 5, i))) return true;
        return false;
    }

    static boolean someBrokenSkew(Sim.Mode m, int n, int r, int w, int rounds) {
        for (int i = 0; i < rounds; i++) {
            Sim s = rig(m, n, r, w, i); s.clockSkew = 40; s.writers = 3;
            if (!lin(s.run(4, 5, i))) return true;
        }
        return false;
    }

    /** No hiccups, no violations — which is exactly why this bug reaches production. */
    static boolean calmIsClean(Sim.Mode m, int rounds) {
        for (int i = 0; i < rounds; i++) {
            Sim s = new Sim(m, 3, 2, 2, i); s.jitterProb = 0.0; s.writers = 1;
            if (!lin(s.run(4, 5, i))) return false;
        }
        return true;
    }

    static boolean lazyOk(int n, int r, int w, int rounds) {
        for (int i = 0; i < rounds; i++) {
            Sim s = rig(Sim.Mode.QUORUM_ABD, n, r, w, i); s.lazyWriteBack = true;
            if (!lin(s.run(4, 5, i))) return false;
        }
        return true;
    }

    /** ABD is a multi-writer algorithm, not just a single-writer one. Worth checking, not assuming. */
    static boolean manyWritersOk(int rounds) {
        for (int i = 0; i < rounds; i++) {
            Sim s = rig(Sim.Mode.QUORUM_ABD, 3, 2, 2, i); s.writers = 4; s.jitterProb = 0.35;
            if (!lin(s.run(4, 5, i))) { System.out.println("        (round " + i + " broke)"); return false; }
        }
        return true;
    }

    /** ...and it is the (timestamp, writer) tiebreak that makes the multi-writer case work. */
    static boolean tiebreakMatters(int rounds) {
        for (int i = 0; i < rounds; i++) {
            Sim s = rig(Sim.Mode.QUORUM_ABD, 3, 2, 2, i); s.writers = 4; s.jitterProb = 0.35; s.noTiebreak = true;
            if (!lin(s.run(4, 5, i))) return true;
        }
        return false;
    }

    /** The locality theorem, checked rather than quoted. */
    static boolean mergeStaysLinearizable(int rounds) {
        for (int i = 0; i < rounds; i++) {
            History x = rig(Sim.Mode.QUORUM_ABD, 3, 2, 2, i).run(3, 4, i);
            History y = rig(Sim.Mode.QUORUM_ABD, 3, 2, 2, 10000 + i).run(3, 4, i);
            for (Op o : y.ops) { o.obj = "y"; o.client += 100; }
            History both = new History(); both.name = "x+y";
            both.ops.addAll(x.ops); both.ops.addAll(y.ops);
            if (!lin(x) || !lin(y)) continue;
            if (!lin(both)) return false;
        }
        return true;
    }

    static History firstBroken(Sim proto, int clients, int perClient, int rounds) {
        for (int i = 0; i < rounds; i++) {
            Sim s = rig(proto.mode, proto.n, proto.r, proto.w, i);
            History h = s.run(clients, perClient, i);
            if (!lin(h)) return h;
        }
        return null;
    }
}
