import java.util.List;
import java.util.Map;

/**
 * Scenario tests = executable documentation for the event-sourcing lab.
 * Run: java -cp ../lib/h2.jar EsTest.java
 *
 * Each test name is a scenario from README.md, and each one opens its own
 * store, so the twelve read as twelve independent stories.
 */
public class EsTest {

    static int passed = 0, failed = 0;

    public static void main(String[] args) {
        // Leave the directory as we found it (see EsLab.deleteDatabaseFiles).
        Runtime.getRuntime().addShutdownHook(new Thread(EsLab::deleteDatabaseFiles));
        // -- state is a replay -------------------------------------------------
        scenario("current_state_is_a_fold_over_the_events",
                EsTest::current_state_is_a_fold_over_the_events);
        scenario("a_rejected_command_appends_nothing",
                EsTest::a_rejected_command_appends_nothing);
        scenario("streams_are_isolated",
                EsTest::streams_are_isolated);

        // -- time travel -------------------------------------------------------
        scenario("state_can_be_reconstructed_as_of_any_past_version",
                EsTest::state_can_be_reconstructed_as_of_any_past_version);

        // -- optimistic concurrency --------------------------------------------
        scenario("stale_writer_gets_a_conflict_not_a_lost_update",
                EsTest::stale_writer_gets_a_conflict_not_a_lost_update);
        scenario("retry_re_decides_and_the_invariant_holds_against_fresh_state",
                EsTest::retry_re_decides_and_the_invariant_holds_against_fresh_state);
        scenario("retry_succeeds_when_the_re_decision_is_still_valid",
                EsTest::retry_succeeds_when_the_re_decision_is_still_valid);

        // -- immutable history -------------------------------------------------
        scenario("corrections_are_new_events_and_history_keeps_both",
                EsTest::corrections_are_new_events_and_history_keeps_both);

        // -- snapshots ---------------------------------------------------------
        scenario("snapshot_load_replays_only_the_tail",
                EsTest::snapshot_load_replays_only_the_tail);
        scenario("snapshot_state_equals_full_replay",
                EsTest::snapshot_state_equals_full_replay);

        // -- schema evolution --------------------------------------------------
        scenario("old_event_versions_are_upcast_on_read",
                EsTest::old_event_versions_are_upcast_on_read);
        scenario("unknown_event_types_are_tolerated_on_replay",
                EsTest::unknown_event_types_are_tolerated_on_replay);

        System.out.println();
        System.out.printf("%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    /** An account-7 that exists but holds nothing. */
    static EsLab.EventStore bank() {
        EsLab.EventStore store = new EsLab.EventStore();
        EsLab.handle(store, "7", a -> a.decideOpen("priya"));
        return store;
    }

    // ------------------------------------------------------------------ tests

    /** Nothing stores a balance; the number comes out of folding three events. */
    static void current_state_is_a_fold_over_the_events() {
        try (EsLab.EventStore store = bank()) {
            EsLab.handle(store, "7", a -> a.decideDeposit(100.0));
            EsLab.handle(store, "7", a -> a.decideWithdraw(30.0));
            EsLab.Loaded loaded = EsLab.Account.load(store, "7");
            assertEquals(List.of(70.0, 3, 3),
                    List.of(loaded.account().balance(), loaded.account().version(), loaded.replayed()),
                    "(balance, version, replayed)");
        }
    }

    /** A refusal is not a fact: rejected commands leave the log untouched. */
    static void a_rejected_command_appends_nothing() {
        try (EsLab.EventStore store = bank()) {
            int before = store.streamVersion("account-7");
            assertRejected(() -> EsLab.handle(store, "7", a -> a.decideWithdraw(1.0)),   // balance is 0
                    "withdrawing from an empty account");
            assertEquals(before, store.streamVersion("account-7"), "stream version");
        }
    }

    static void streams_are_isolated() {
        try (EsLab.EventStore store = bank()) {
            EsLab.handle(store, "8", a -> a.decideOpen("amit"));
            EsLab.handle(store, "8", a -> a.decideDeposit(500.0));
            assertEquals(0.0, EsLab.Account.load(store, "7").account().balance(),
                    "account 7 must not see account 8's money");
        }
    }

    static void state_can_be_reconstructed_as_of_any_past_version() {
        try (EsLab.EventStore store = bank()) {
            EsLab.handle(store, "7", a -> a.decideDeposit(100.0));
            EsLab.handle(store, "7", a -> a.decideWithdraw(30.0));
            assertEquals(100.0,
                    EsLab.Account.load(store, "7", /* useSnapshot */ false, /* asOfVersion */ 2).account().balance(),
                    "as of version 2");
            assertEquals(70.0,
                    EsLab.Account.load(store, "7", /* useSnapshot */ false, /* asOfVersion */ 3).account().balance(),
                    "as of version 3");
        }
    }

    static void stale_writer_gets_a_conflict_not_a_lost_update() {
        try (EsLab.EventStore store = bank()) {
            EsLab.handle(store, "7", a -> a.decideDeposit(70.0));
            EsLab.Account t1 = EsLab.Account.load(store, "7").account();
            EsLab.Account t2 = EsLab.Account.load(store, "7").account();       // same version
            store.append("account-7", t1.version(), t1.decideWithdraw(50.0));
            assertConflict(() -> store.append("account-7", t2.version(), t2.decideWithdraw(40.0)),
                    "the second append at a version that has moved");
        }
    }

    static void retry_re_decides_and_the_invariant_holds_against_fresh_state() {
        try (EsLab.EventStore store = bank()) {
            EsLab.handle(store, "7", a -> a.decideDeposit(70.0));
            EsLab.Account t1 = EsLab.Account.load(store, "7").account();
            store.append("account-7", t1.version(), t1.decideWithdraw(50.0));
            // handle() reloads (balance now 20) and re-decides: 40 must be rejected
            assertRejected(() -> EsLab.handle(store, "7", a -> a.decideWithdraw(40.0)),
                    "the re-decided withdrawal");
            assertEquals(20.0, EsLab.Account.load(store, "7").account().balance(), "never overdrawn");
        }
    }

    static void retry_succeeds_when_the_re_decision_is_still_valid() {
        try (EsLab.EventStore store = bank()) {
            EsLab.handle(store, "7", a -> a.decideDeposit(100.0));
            EsLab.Account.load(store, "7");
            // someone else deposits meanwhile — a stale append would conflict...
            EsLab.handle(store, "7", a -> a.decideDeposit(5.0));
            // ...but handle()'s retry loop absorbs it: a 40 withdrawal is still fine
            EsLab.handle(store, "7", a -> a.decideWithdraw(40.0));
            assertEquals(65.0, EsLab.Account.load(store, "7").account().balance(), "balance");
        }
    }

    static void corrections_are_new_events_and_history_keeps_both() {
        try (EsLab.EventStore store = bank()) {
            EsLab.handle(store, "7", a -> List.of(new EsLab.Event("MoneyDeposited",
                    EsLab.payload("amount", 500.0, "channel", "wire"))));
            EsLab.handle(store, "7", a -> List.of(new EsLab.Event("DepositReversed",
                    EsLab.payload("amount", 500.0, "reason", "wrong account"))));
            assertEquals(0.0, EsLab.Account.load(store, "7").account().balance(), "balance");
            List<String> types = store.read("account-7").stream().map(EsLab.Recorded::type).toList();
            assertTrue(types.contains("MoneyDeposited") && types.contains("DepositReversed"),
                    "the mistake AND the correction are both facts, got " + types);
        }
    }

    static void snapshot_load_replays_only_the_tail() {
        try (EsLab.EventStore store = bank()) {
            for (int i = 0; i < 130; i++) {
                EsLab.handle(store, "7", a -> a.decideDeposit(1.0));
                EsLab.maybeSnapshot(store, "7");
            }
            int cold = EsLab.Account.load(store, "7").replayed();
            int warm = EsLab.Account.load(store, "7", /* useSnapshot */ true, /* asOfVersion */ null).replayed();
            assertEquals(131, cold, "cold load");
            assertTrue(warm < 40, "snapshot load replayed " + warm + " events; expected only the tail");
        }
    }

    static void snapshot_state_equals_full_replay() {
        try (EsLab.EventStore store = bank()) {
            for (int i = 0; i < 130; i++) {
                EsLab.handle(store, "7", a -> a.decideDeposit(1.0));
                EsLab.maybeSnapshot(store, "7");
            }
            assertEquals(EsLab.Account.load(store, "7", /* useSnapshot */ true, /* asOfVersion */ null).account(),
                    EsLab.Account.load(store, "7").account(),
                    "snapshots are pure cache");
        }
    }

    static void old_event_versions_are_upcast_on_read() {
        try (EsLab.EventStore store = new EsLab.EventStore()) {
            store.append("account-9", 0, List.of(new EsLab.Event("AccountOpened",
                    EsLab.payload("owner", "amit"))));
            store.append("account-9", 1, List.of(new EsLab.Event("MoneyDeposited",
                    EsLab.payload("amount", 10.0))), 1);
            List<EsLab.Recorded> stream = store.read("account-9");
            Map<String, Object> payload = stream.get(stream.size() - 1).payload();
            assertEquals(EsLab.payload("amount", 10.0, "channel", "branch"), payload,
                    "v1 events gain the field with the historically-correct default");
        }
    }

    static void unknown_event_types_are_tolerated_on_replay() {
        try (EsLab.EventStore store = bank()) {
            store.append("account-7", 1, List.of(new EsLab.Event("SomeFutureEventType",
                    EsLab.payload("x", 1))));
            EsLab.Account acct = EsLab.Account.load(store, "7").account();   // must not crash
            assertEquals(2, acct.version(), "version");
        }
    }

    // ---------------------------------------------------------------- helpers

    interface Body { void run(); }

    /** One PASS/FAIL line per scenario; a failure never stops the rest. */
    static void scenario(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println("  PASS  " + name);
        } catch (AssertionError e) {
            failed++;
            System.out.println("  FAIL  " + name + "  (" + e.getMessage() + ")");
        } catch (RuntimeException e) {
            failed++;
            System.out.println("  FAIL  " + name + "  (threw " + e + ")");
        }
    }

    static void assertEquals(Object expected, Object actual, String what) {
        if (!expected.equals(actual))
            throw new AssertionError(what + ": expected " + expected + ", got " + actual);
    }

    static void assertTrue(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }

    static void assertRejected(Runnable command, String what) {
        try {
            command.run();
        } catch (EsLab.CommandRejected e) {
            return;
        }
        throw new AssertionError(what + ": expected CommandRejected, but it was accepted");
    }

    static void assertConflict(Runnable command, String what) {
        try {
            command.run();
        } catch (EsLab.ConcurrencyConflict e) {
            return;
        }
        throw new AssertionError(what + ": expected ConcurrencyConflict, but it was accepted");
    }
}
