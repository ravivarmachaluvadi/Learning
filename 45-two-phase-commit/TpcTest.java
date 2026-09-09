import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Executable documentation: each test name is a scenario from README.md.
 *
 *   java TpcTest.java
 *
 * 12 scenarios. Prints one PASS/FAIL line per scenario and exits non-zero if
 * any failed. (Classes live in TpcLab.java, in the default package, so they
 * are named TpcLab.Bank / TpcLab.Coordinator rather than imported.)
 */
public class TpcTest {

    static TpcLab.Coordinator quiet() {
        return new TpcLab.Coordinator(false);
    }

    static int passed = 0, failed = 0;

    public static void main(String[] args) {
        System.out.println("Running TpcTest against TpcLab");
        System.out.println();

        // --- phase 1 scenarios ---
        run("test_happy_path_commits_both_banks_atomically", TpcTest::test_happy_path_commits_both_banks_atomically);
        run("test_single_no_vote_aborts_everyone", TpcTest::test_single_no_vote_aborts_everyone);
        run("test_insufficient_funds_is_a_no_vote", TpcTest::test_insufficient_funds_is_a_no_vote);
        run("test_participant_silence_before_voting_is_a_safe_abort", TpcTest::test_participant_silence_before_voting_is_a_safe_abort);
        run("test_prepared_account_blocks_other_customers", TpcTest::test_prepared_account_blocks_other_customers);

        // --- commit point scenarios ---
        run("test_decision_log_write_is_the_commit_point", TpcTest::test_decision_log_write_is_the_commit_point);
        run("test_crash_before_decision_means_presumed_abort", TpcTest::test_crash_before_decision_means_presumed_abort);

        // --- blocking and recovery scenarios ---
        run("test_prepared_banks_block_holding_locks_while_coordinator_down",
                TpcTest::test_prepared_banks_block_holding_locks_while_coordinator_down);
        run("test_recovered_coordinator_resends_decision_and_unblocks",
                TpcTest::test_recovered_coordinator_resends_decision_and_unblocks);
        run("test_recovery_completion_is_idempotent_for_participants",
                TpcTest::test_recovery_completion_is_idempotent_for_participants);
        run("test_participant_recovers_in_doubt_and_asks_coordinator",
                TpcTest::test_participant_recovers_in_doubt_and_asks_coordinator);
        run("test_same_participant_history_can_end_in_opposite_outcomes",
                TpcTest::test_same_participant_history_can_end_in_opposite_outcomes);

        System.out.println();
        System.out.printf("%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------- phase 1 scenarios

    static void test_happy_path_commits_both_banks_atomically() {
        TpcLab.Bank[] banks = TpcLab.freshBanks();
        TpcLab.Bank a = banks[0], b = banks[1];
        String result = quiet().runTransfer("t1", TpcLab.transferOps(a, b));
        eq("COMMITTED", result, "outcome");
        eq(500, a.accounts.get("alice"), "alice");
        eq(700, b.accounts.get("bob"), "bob");
        isTrue(a.locks.isEmpty(), "locks released after decision");
    }

    static void test_single_no_vote_aborts_everyone() {
        TpcLab.Bank[] banks = TpcLab.freshBanks();
        TpcLab.Bank a = banks[0], b = banks[1];
        b.voteNo = true;
        String result = quiet().runTransfer("t2", TpcLab.transferOps(a, b));
        eq("ABORTED", result, "outcome");
        eq(1000, a.accounts.get("alice"), "debit undone-by-never");
        eq(200, b.accounts.get("bob"), "bob");
        isTrue(a.locks.isEmpty(), "prepared lock released on abort");
    }

    static void test_insufficient_funds_is_a_no_vote() {
        TpcLab.Bank[] banks = TpcLab.freshBanks();
        TpcLab.Bank a = banks[0], b = banks[1];
        String result = quiet().runTransfer("t3", TpcLab.transferOps(a, b, 5000));
        eq("ABORTED", result, "outcome");
    }

    static void test_participant_silence_before_voting_is_a_safe_abort() {
        TpcLab.Bank[] banks = TpcLab.freshBanks();
        TpcLab.Bank a = banks[0], b = banks[1];
        b.crashBeforeVote = true;
        String result = quiet().runTransfer("t4", TpcLab.transferOps(a, b));
        eq("ABORTED", result, "outcome");
        eq(1000, a.accounts.get("alice"), "alice");
    }

    static void test_prepared_account_blocks_other_customers() {
        TpcLab.Bank[] banks = TpcLab.freshBanks();
        TpcLab.Bank a = banks[0], b = banks[1];
        a.prepare("t5", "alice", -500);          // phase 1 only; no decision yet
        expectBlocked(() -> a.readBalance("alice"), "locked row must block");
        eq(200, b.readBalance("bob"), "unlocked rows readable");
    }

    // -------------------------------------------- commit point scenarios

    static void test_decision_log_write_is_the_commit_point() {
        TpcLab.Bank[] banks = TpcLab.freshBanks();
        TpcLab.Bank a = banks[0], b = banks[1];
        TpcLab.Coordinator c = quiet();
        c.crashAfterDecisionLogged = true;
        expectCrash(() -> c.runTransfer("t6", TpcLab.transferOps(a, b)), "coordinator dies after the write");
        // nobody was told, yet the transaction IS committed:
        eq("COMMIT", c.decisionFor("t6"), "decision on disk");
        eq(1000, a.accounts.get("alice"), "not yet applied anywhere");
    }

    static void test_crash_before_decision_means_presumed_abort() {
        TpcLab.Bank[] banks = TpcLab.freshBanks();
        TpcLab.Bank a = banks[0], b = banks[1];
        TpcLab.Coordinator c = quiet();
        c.crashBeforeDecisionLogged = true;
        expectCrash(() -> c.runTransfer("t7", TpcLab.transferOps(a, b)), "coordinator dies before the write");
        eq("ABORT", c.decisionFor("t7"), "no decision in log -> safe to abort");
    }

    // --------------------------------- blocking and recovery scenarios

    static void test_prepared_banks_block_holding_locks_while_coordinator_down() {
        TpcLab.Bank[] banks = TpcLab.freshBanks();
        TpcLab.Bank a = banks[0], b = banks[1];
        TpcLab.Coordinator c = quiet();
        c.crashAfterDecisionLogged = true;
        expectCrash(() -> c.runTransfer("t8", TpcLab.transferOps(a, b)), "coordinator dies");
        eq("PREPARED", a.state.get("t8"), "in doubt");
        eq("PREPARED", b.state.get("t8"), "in doubt");
        expectBlocked(() -> a.readBalance("alice"), "alice still locked");
        expectBlocked(() -> b.readBalance("bob"), "bob still locked");
    }

    static void test_recovered_coordinator_resends_decision_and_unblocks() {
        TpcLab.Bank[] banks = TpcLab.freshBanks();
        TpcLab.Bank a = banks[0], b = banks[1];
        TpcLab.Coordinator c = quiet();
        c.crashAfterDecisionLogged = true;
        expectCrash(() -> c.runTransfer("t9", TpcLab.transferOps(a, b)), "coordinator dies");
        TpcLab.Coordinator c2 = hushed(
                () -> TpcLab.Coordinator.recover(c.log, Map.of("t9", TpcLab.transferOps(a, b))));
        eq(500, a.accounts.get("alice"), "alice");
        eq(700, b.accounts.get("bob"), "bob");
        eq(500, a.readBalance("alice"), "unblocked");
        eq("COMMIT", c2.decisionFor("t9"), "decision survives the restart");
    }

    static void test_recovery_completion_is_idempotent_for_participants() {
        TpcLab.Bank[] banks = TpcLab.freshBanks();
        TpcLab.Bank a = banks[0], b = banks[1];
        TpcLab.Coordinator c = quiet();
        c.crashAfterDecisionLogged = true;
        expectCrash(() -> c.runTransfer("t10", TpcLab.transferOps(a, b)), "coordinator dies");
        hushed(() -> TpcLab.Coordinator.recover(c.log, Map.of("t10", TpcLab.transferOps(a, b))));
        hushed(() -> TpcLab.Coordinator.recover(c.log, Map.of("t10", TpcLab.transferOps(a, b))));  // again
        eq(500, a.accounts.get("alice"), "applied exactly once");
    }

    static void test_participant_recovers_in_doubt_and_asks_coordinator() {
        TpcLab.Bank[] banks = TpcLab.freshBanks();
        TpcLab.Bank a = banks[0], b = banks[1];
        TpcLab.Coordinator c = quiet();
        a.crashAfterVote = true;    // PREPARED hits A's log; the YES is lost
        String result = c.runTransfer("t11", TpcLab.transferOps(a, b));
        eq("ABORTED", result, "coordinator saw silence from A -> timeout -> NO");
        // bank A restarts from its durable log with boot-time balances:
        TpcLab.Recovered restarted = TpcLab.Bank.recover("BANK-A", Map.of("alice", 1000), a.log);
        TpcLab.Bank a2 = restarted.bank();
        eq(List.of("t11"), restarted.inDoubt(), "PREPARED with no outcome");
        expectBlocked(() -> a2.readBalance("alice"), "promise stands across the restart");
        a2.askCoordinator(c, "t11");
        eq("ABORTED", a2.state.get("t11"), "verdict learned by asking");
        isTrue(!a2.locks.containsKey("alice"), "lock released once the doubt is resolved");
        eq(1000, a2.readBalance("alice"), "alice readable again");
    }

    /** The heart of the blocking argument (experiment 6). */
    static void test_same_participant_history_can_end_in_opposite_outcomes() {
        List<Integer> endings = new ArrayList<>();
        String[][] worlds = {{"w1", "after"}, {"w2", "before"}};
        for (String[] world : worlds) {
            String knob = world[1];
            TpcLab.Bank[] banks = TpcLab.freshBanks();
            TpcLab.Bank a = banks[0], b = banks[1];
            TpcLab.Coordinator c = quiet();
            if (knob.equals("after")) c.crashAfterDecisionLogged = true;
            else c.crashBeforeDecisionLogged = true;
            expectCrash(() -> c.runTransfer("t12", TpcLab.transferOps(a, b)), "coordinator dies " + knob);
            eq("PREPARED", a.state.get("t12"), "identical local state in both worlds");
            hushed(() -> TpcLab.Coordinator.recover(c.log, Map.of("t12", TpcLab.transferOps(a, b))));
            endings.add(a.accounts.get("alice"));
        }
        eq(List.of(500, 1000), endings, "opposite global outcomes from identical local view");
    }

    // ---------------------------------------------------------------- helpers

    static void run(String name, Runnable test) {
        try {
            test.run();
            passed++;
            System.out.println("  PASS  " + name);
        } catch (AssertionError e) {
            failed++;
            System.out.println("  FAIL  " + name + "  (" + e.getMessage() + ")");
        } catch (RuntimeException e) {
            failed++;
            System.out.println("  FAIL  " + name + "  (unexpected " + e + ")");
        }
    }

    /**
     * Coordinator.recover() narrates by design — a restarted coordinator
     * announces the phase 2 it is re-sending. Swallow that here so the runner
     * prints only PASS/FAIL lines; the behaviour under test is unchanged.
     */
    static <T> T hushed(Supplier<T> body) {
        PrintStream original = System.out;
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        try {
            return body.get();
        } finally {
            System.setOut(original);
        }
    }

    static void eq(Object expected, Object actual, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + ", got " + actual);
        }
    }

    static void isTrue(boolean condition, String what) {
        if (!condition) throw new AssertionError(what + ": expected true");
    }

    static void expectCrash(Runnable body, String what) {
        try {
            body.run();
        } catch (TpcLab.Crash e) {
            return;
        }
        throw new AssertionError(what + ": expected a Crash, none thrown");
    }

    static void expectBlocked(Runnable body, String what) {
        try {
            body.run();
        } catch (TpcLab.Blocked e) {
            return;
        }
        throw new AssertionError(what + ": expected Blocked, none thrown");
    }
}
