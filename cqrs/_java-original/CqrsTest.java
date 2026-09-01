import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scenario tests = executable documentation for the CQRS lab.
 *
 * <pre>
 *   java -cp ../lib/h2.jar CqrsTest.java
 * </pre>
 *
 * Each test name is a scenario from README.md. No JUnit — H2 is the lab's only
 * dependency, so this is a plain main() that runs each scenario and counts it.
 */
public class CqrsTest {

    static final String DAY = "2026-08-27";

    /** A write side, a read side, and the projector between them. */
    record Shop(CqrsLab.WriteSide w, CqrsLab.ReadSide r, CqrsLab.Projector p) {}

    static Shop shop() {
        CqrsLab.WriteSide w = new CqrsLab.WriteSide();
        CqrsLab.ReadSide r = new CqrsLab.ReadSide();
        return new Shop(w, r, new CqrsLab.Projector(w, r));
    }

    static CqrsLab.Item item(String product, int qty, double price) {
        return new CqrsLab.Item(product, qty, price);
    }

    static int passed = 0, failed = 0;

    public static void main(String[] args) {
        // CommandSideScenarios
        scenario("test_command_that_violates_an_invariant_is_rejected_and_emits_nothing",
                CqrsTest::commandThatViolatesAnInvariantIsRejectedAndEmitsNothing);
        scenario("test_cannot_cancel_a_shipped_order_and_no_event_is_emitted_for_the_attempt",
                CqrsTest::cannotCancelAShippedOrderAndNoEventIsEmittedForTheAttempt);
        // ConsistencyWindowScenarios
        scenario("test_query_is_stale_until_the_projector_runs",
                CqrsTest::queryIsStaleUntilTheProjectorRuns);
        scenario("test_query_is_fresh_after_the_projector_catches_up",
                CqrsTest::queryIsFreshAfterTheProjectorCatchesUp);
        scenario("test_read_your_own_writes_via_wait_for",
                CqrsTest::readYourOwnWritesViaWaitFor);
        // RebuildScenarios
        scenario("test_read_model_rebuilt_from_scratch_matches_the_incrementally_built_one",
                CqrsTest::readModelRebuiltFromScratchMatchesTheIncrementallyBuiltOne);
        scenario("test_a_read_model_invented_later_backfills_from_old_events",
                CqrsTest::aReadModelInventedLaterBackfillsFromOldEvents);
        // DeliveryFaultScenarios
        scenario("test_duplicate_delivery_is_harmless_when_the_projection_is_idempotent",
                CqrsTest::duplicateDeliveryIsHarmlessWhenTheProjectionIsIdempotent);
        scenario("test_duplicate_delivery_double_counts_when_the_projection_is_naive",
                CqrsTest::duplicateDeliveryDoubleCountsWhenTheProjectionIsNaive);
        scenario("test_crash_mid_batch_with_atomic_checkpoint_is_all_or_nothing",
                CqrsTest::crashMidBatchWithAtomicCheckpointIsAllOrNothing);
        scenario("test_crash_mid_batch_without_atomic_checkpoint_corrupts_a_naive_projection",
                CqrsTest::crashMidBatchWithoutAtomicCheckpointCorruptsANaiveProjection);

        System.out.println();
        System.out.printf("%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------- CommandSideScenarios

    static void commandThatViolatesAnInvariantIsRejectedAndEmitsNothing() {
        Shop s = shop();
        assertRejected(() -> s.w().placeOrder("ord-1", "priya", List.of(item("keyboard", -1, 80.0)), DAY));
        assertEquals(0, s.w().lastSeq(), "a rejected command must not leak an event");
    }

    static void cannotCancelAShippedOrderAndNoEventIsEmittedForTheAttempt() {
        Shop s = shop();
        s.w().placeOrder("ord-1", "priya", List.of(item("keyboard", 1, 80.0)), DAY);
        s.w().markShipped("ord-1");
        int before = s.w().lastSeq();
        assertRejected(() -> s.w().cancelOrder("ord-1", DAY));
        assertEquals(before, s.w().lastSeq());
    }

    // ------------------------------------------- ConsistencyWindowScenarios

    static void queryIsStaleUntilTheProjectorRuns() {
        Shop s = shop();
        s.w().placeOrder("ord-1", "priya", List.of(item("keyboard", 1, 80.0)), DAY);
        assertEquals(List.of(), s.r().dashboard("priya"), "the write happened; the read side hasn't heard yet");
        assertEquals(1, s.p().lag());
    }

    static void queryIsFreshAfterTheProjectorCatchesUp() {
        Shop s = shop();
        s.w().placeOrder("ord-1", "priya", List.of(item("keyboard", 1, 80.0), item("mouse", 2, 20.0)), DAY);
        s.p().runOnce();
        assertEquals(List.of(new CqrsLab.DashboardRow("ord-1", "PLACED", 120.0)), s.r().dashboard("priya"));
        assertEquals(120.0, s.r().revenue(DAY));
        assertEquals(0, s.p().lag());
    }

    static void readYourOwnWritesViaWaitFor() {
        Shop s = shop();
        s.w().placeOrder("ord-1", "priya", List.of(item("keyboard", 1, 80.0)), DAY);
        CqrsLab.waitFor(s.p(), s.w().lastSeq());   // the "wait until you can see me" technique
        assertEquals(1, s.r().dashboard("priya").size());
    }

    // ------------------------------------------------------ RebuildScenarios

    static void readModelRebuiltFromScratchMatchesTheIncrementallyBuiltOne() {
        Shop s = shop();
        s.w().placeOrder("ord-1", "priya", List.of(item("keyboard", 1, 80.0)), DAY);
        s.p().runOnce();
        s.w().placeOrder("ord-2", "amit", List.of(item("desk", 1, 300.0)), DAY);
        s.w().cancelOrder("ord-2", DAY);
        s.p().runOnce();

        CqrsLab.ReadSide fresh = new CqrsLab.ReadSide("readmodel-fresh");   // "drop the read database"
        new CqrsLab.Projector(s.w(), fresh).runOnce();                      // replay history
        assertEquals(s.r().dashboard("priya"), fresh.dashboard("priya"));
        assertEquals(s.r().dashboard("amit"), fresh.dashboard("amit"));
        assertEquals(s.r().revenue(DAY), fresh.revenue(DAY));
    }

    static void aReadModelInventedLaterBackfillsFromOldEvents() {
        Shop s = shop();
        s.w().placeOrder("ord-1", "priya", List.of(item("keyboard", 2, 80.0), item("mouse", 1, 20.0)), DAY);
        s.w().placeOrder("ord-2", "amit", List.of(item("keyboard", 1, 80.0)), DAY);
        // Months later, someone asks a question nobody designed for:
        // "what are our top products by units sold?" Build it from history.
        Map<String, Integer> units = new LinkedHashMap<>();
        for (CqrsLab.Event event : s.w().eventsAfter(0)) {
            if (event.type().equals("OrderPlaced")) {
                for (CqrsLab.Payload line : event.payload().list("items")) {
                    units.merge(line.str("product"), line.count("qty"), Integer::sum);
                }
            }
        }
        assertEquals(Map.of("keyboard", 3, "mouse", 1), units);
    }

    // ----------------------------------------------- DeliveryFaultScenarios

    static void redeliverEverything(CqrsLab.ReadSide read) {
        read.db.update("UPDATE projector_checkpoint SET last_seq=0");
        read.db.commit();
    }

    static void duplicateDeliveryIsHarmlessWhenTheProjectionIsIdempotent() {
        Shop s = shop();
        s.w().placeOrder("ord-1", "priya", List.of(item("keyboard", 1, 80.0)), DAY);
        s.w().cancelOrder("ord-1", DAY);
        s.p().runOnce();
        redeliverEverything(s.r());                   // at-least-once delivery strikes
        s.p().runOnce();
        assertEquals(0.0, s.r().revenue(DAY), "place then cancel = 0, even after replay");
        assertEquals(List.of(new CqrsLab.DashboardRow("ord-1", "CANCELLED", 80.0)), s.r().dashboard("priya"));
    }

    static void duplicateDeliveryDoubleCountsWhenTheProjectionIsNaive() {
        CqrsLab.WriteSide w = new CqrsLab.WriteSide();
        CqrsLab.ReadSide r = new CqrsLab.ReadSide();
        CqrsLab.Projector naive = new CqrsLab.Projector(w, r, false, true);
        w.placeOrder("ord-1", "priya", List.of(item("keyboard", 1, 80.0)), DAY);
        naive.runOnce();
        redeliverEverything(r);
        naive.runOnce();
        assertEquals(160.0, r.revenue(DAY), "the bug we are demonstrating: 80 counted twice");
    }

    static void crashMidBatchWithAtomicCheckpointIsAllOrNothing() {
        Shop s = shop();
        s.w().placeOrder("ord-1", "priya", List.of(item("keyboard", 1, 80.0)), DAY);
        s.w().placeOrder("ord-2", "amit", List.of(item("desk", 1, 300.0)), DAY);
        assertCrashes(() -> s.p().runOnce(100, true));
        // nothing half-applied, checkpoint untouched:
        assertEquals(List.of(), s.r().dashboard("priya"));
        assertEquals(0, s.r().checkpoint());
        s.p().runOnce();                              // restart: everything applies exactly once
        assertEquals(380.0, s.r().revenue(DAY));
    }

    static void crashMidBatchWithoutAtomicCheckpointCorruptsANaiveProjection() {
        CqrsLab.WriteSide w = new CqrsLab.WriteSide();
        CqrsLab.ReadSide r = new CqrsLab.ReadSide();
        CqrsLab.Projector broken = new CqrsLab.Projector(w, r, false, false);
        w.placeOrder("ord-1", "priya", List.of(item("keyboard", 1, 80.0)), DAY);
        assertCrashes(() -> broken.runOnce(100, true));   // applied, committed, then died pre-checkpoint
        broken.runOnce();                                 // restart redelivers from checkpoint 0
        assertEquals(160.0, r.revenue(DAY),
                "80 became 160: partial commit + redelivery + non-idempotent apply");
    }

    // ---------------------------------------------------------------- helpers

    /** One scenario = one PASS/FAIL line, however many assertions it makes. */
    static void scenario(String name, Runnable body) {
        try {
            body.run();
            passed++;
            System.out.println("  PASS  " + name);
        } catch (AssertionFailed e) {
            failed++;
            System.out.println("  FAIL  " + name + "  (" + e.getMessage() + ")");
        } catch (RuntimeException e) {
            failed++;
            System.out.println("  FAIL  " + name + "  (unexpected " + e.getClass().getSimpleName()
                    + ": " + e.getMessage() + ")");
        }
    }

    static class AssertionFailed extends RuntimeException {
        AssertionFailed(String message) { super(message); }
    }

    static void assertEquals(Object expected, Object actual) {
        assertEquals(expected, actual, null);
    }

    static void assertEquals(Object expected, Object actual, String note) {
        if (!expected.equals(actual)) {
            throw new AssertionFailed("expected " + expected + ", got " + actual
                    + (note == null ? "" : " — " + note));
        }
    }

    /** The command must be refused, and refused for a business reason. */
    static void assertRejected(Runnable command) {
        assertThrows(CqrsLab.CommandRejected.class, command);
    }

    /** The projector must die where we told it to. */
    static void assertCrashes(Runnable body) {
        assertThrows(CqrsLab.ProjectorCrashed.class, body);
    }

    static void assertThrows(Class<? extends RuntimeException> expected, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            if (expected.isInstance(e)) return;
            throw new AssertionFailed("expected " + expected.getSimpleName()
                    + ", got " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        throw new AssertionFailed("expected " + expected.getSimpleName() + ", nothing was thrown");
    }
}
