import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executable documentation: each test name is a scenario from README.md.
 *
 * Run:  java SagaTest.java
 */
public class SagaTest {

    static SagaLab.Orchestrator quiet(SagaLab.World world) {
        return new SagaLab.Orchestrator(world.services(), world.log(), false);
    }

    static int passed = 0, failed = 0;

    public static void main(String[] args) {
        System.out.println("Running SagaTest — 11 scenarios from README.md");
        System.out.println();

        // -------------------------------------------------- OrchestrationScenarios
        run("test_happy_path_books_all_three_services",
                SagaTest::test_happy_path_books_all_three_services);
        run("test_failure_at_car_compensates_hotel_then_flight",
                SagaTest::test_failure_at_car_compensates_hotel_then_flight);
        run("test_compensations_run_in_reverse_order",
                SagaTest::test_compensations_run_in_reverse_order);
        run("test_failure_at_first_step_compensates_nothing",
                SagaTest::test_failure_at_first_step_compensates_nothing);
        run("test_orchestrator_crash_then_recovery_completes_saga",
                SagaTest::test_orchestrator_crash_then_recovery_completes_saga);
        run("test_recovery_retry_does_not_double_book_thanks_to_idempotency",
                SagaTest::test_recovery_retry_does_not_double_book_thanks_to_idempotency);
        run("test_completed_steps_are_skipped_on_recovery",
                SagaTest::test_completed_steps_are_skipped_on_recovery);
        run("test_failing_compensation_is_retried_until_it_succeeds",
                SagaTest::test_failing_compensation_is_retried_until_it_succeeds);
        run("test_mid_saga_state_is_visible_to_outsiders_no_isolation",
                SagaTest::test_mid_saga_state_is_visible_to_outsiders_no_isolation);

        // --------------------------------------------------- ChoreographyScenarios
        run("test_event_chain_books_all_three_services",
                SagaTest::test_event_chain_books_all_three_services);
        run("test_car_failure_ripples_cancellations_backwards",
                SagaTest::test_car_failure_ripples_cancellations_backwards);

        System.out.println();
        System.out.printf("%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------ OrchestrationScenarios

    static void test_happy_path_books_all_three_services() {
        SagaLab.World world = SagaLab.freshWorld(3);
        String result = quiet(world).run("t1");
        assertEquals("COMPLETED", result, "saga result");
        for (String name : List.of("flight", "hotel", "car")) {
            assertEquals("BOOKED", world.services().get(name).bookings.get("t1"), name + " booking");
            assertEquals(2, world.services().get(name).capacity, name + " capacity");
        }
    }

    static void test_failure_at_car_compensates_hotel_then_flight() {
        SagaLab.World world = SagaLab.freshWorld(3);
        world.services().get("car").failNextBook = true;
        String result = quiet(world).run("t2");
        assertEquals("ABORTED", result, "saga result");
        assertEquals("CANCELLED", world.services().get("flight").bookings.get("t2"), "flight booking");
        assertEquals("CANCELLED", world.services().get("hotel").bookings.get("t2"), "hotel booking");
        check(!world.services().get("car").bookings.containsKey("t2"),
                "car must hold no booking for t2");
        // all capacity returned: the trip left no trace on availability
        for (String name : List.of("flight", "hotel", "car"))
            assertEquals(3, world.services().get(name).capacity, name + " capacity");
    }

    static void test_compensations_run_in_reverse_order() {
        List<String> order = new ArrayList<>();
        // stand-in for the Python test's monkey-patch: services that note the
        // name whenever their compensation is called
        Map<String, SagaLab.Service> services = new LinkedHashMap<>();
        services.put("flight", recordsCancel("flight", 3, order));
        services.put("hotel", recordsCancel("hotel", 3, order));
        services.put("car", new SagaLab.Service("car", 3));
        SagaLab.World world = new SagaLab.World(services, new SagaLab.SagaLog());
        services.get("car").failNextBook = true;
        quiet(world).run("t3");
        assertEquals(List.of("hotel", "flight"), order, "compensation order");
    }

    static void test_failure_at_first_step_compensates_nothing() {
        SagaLab.World world = SagaLab.freshWorld(3);
        world.services().get("flight").failNextBook = true;
        String result = quiet(world).run("t4");
        assertEquals("ABORTED", result, "saga result");
        for (String name : List.of("flight", "hotel", "car")) {
            assertEquals(3, world.services().get(name).capacity, name + " capacity");
            check(!world.services().get(name).bookings.containsKey("t4"),
                    name + " must hold no booking for t4");
        }
    }

    static void test_orchestrator_crash_then_recovery_completes_saga() {
        SagaLab.World world = SagaLab.freshWorld(3);
        assertCrashes(() -> quiet(world).run("t5", "hotel", null));
        String result = quiet(world).run("t5");  // new orchestrator
        assertEquals("COMPLETED", result, "saga result after recovery");
    }

    static void test_recovery_retry_does_not_double_book_thanks_to_idempotency() {
        SagaLab.World world = SagaLab.freshWorld(3);
        assertCrashes(() -> quiet(world).run("t6", "hotel", null));
        quiet(world).run("t6");
        // hotel.book was called twice for t6, but only one seat was taken
        assertEquals(2, world.services().get("hotel").capacity, "hotel capacity");
    }

    static void test_completed_steps_are_skipped_on_recovery() {
        List<String> calls = new ArrayList<>();
        Map<String, SagaLab.Service> services = new LinkedHashMap<>();
        services.put("flight", recordsBook("flight", 3, calls));
        services.put("hotel", new SagaLab.Service("hotel", 3));
        services.put("car", new SagaLab.Service("car", 3));
        SagaLab.World world = new SagaLab.World(services, new SagaLab.SagaLog());
        assertCrashes(() -> quiet(world).run("t7", "hotel", null));
        calls.clear();   // only the RECOVERY run's calls count (the Python test
                         // installs its recorder after the crash)
        quiet(world).run("t7");
        check(calls.isEmpty(), "flight:done was in the log; not re-run — but got " + calls);
    }

    static void test_failing_compensation_is_retried_until_it_succeeds() {
        SagaLab.World world = SagaLab.freshWorld(3);
        world.services().get("car").failNextBook = true;
        world.services().get("hotel").failNextCancel = 3;
        String result = quiet(world).run("t8");
        assertEquals("ABORTED", result, "saga result");
        assertEquals("CANCELLED", world.services().get("hotel").bookings.get("t8"), "hotel booking");
        assertEquals(3, world.services().get("hotel").capacity, "hotel capacity");
    }

    static void test_mid_saga_state_is_visible_to_outsiders_no_isolation() {
        SagaLab.World world = SagaLab.freshWorld(1);
        SagaLab.Orchestrator orch = quiet(world);
        assertEquals("PAUSED", orch.run("t9", null, "hotel"), "saga result");
        // another customer looks while the saga is still in flight:
        assertEquals(0, world.services().get("hotel").capacity, "sees 'sold out'");
        world.services().get("car").failNextBook = true;
        orch.run("t9");
        assertEquals(1, world.services().get("hotel").capacity, "later undone");
    }

    // ------------------------------------------------- ChoreographyScenarios

    static void test_event_chain_books_all_three_services() {
        SagaLab.World world = SagaLab.freshWorld(3);
        SagaLab.Bus bus = new SagaLab.Bus(false);
        SagaLab.wireChoreography(bus, world.services());
        bus.publish("TripRequested", "c1");
        assertEquals(List.of("TripRequested", "FlightBooked", "HotelBooked", "CarBooked"),
                bus.history, "bus history");
        for (String name : List.of("flight", "hotel", "car"))
            assertEquals("BOOKED", world.services().get(name).bookings.get("c1"), name + " booking");
    }

    static void test_car_failure_ripples_cancellations_backwards() {
        SagaLab.World world = SagaLab.freshWorld(3);
        SagaLab.Bus bus = new SagaLab.Bus(false);
        SagaLab.wireChoreography(bus, world.services());
        world.services().get("car").failNextBook = true;
        bus.publish("TripRequested", "c2");
        assertEquals("CANCELLED", world.services().get("hotel").bookings.get("c2"), "hotel booking");
        assertEquals("CANCELLED", world.services().get("flight").bookings.get("c2"), "flight booking");
        for (String name : List.of("flight", "hotel", "car"))
            assertEquals(3, world.services().get(name).capacity, name + " capacity");
    }

    // ---------------------------------------------------------------- helpers

    /** A Service that records its name every time its compensation runs. */
    static SagaLab.Service recordsCancel(String name, int capacity, List<String> order) {
        return new SagaLab.Service(name, capacity) {
            @Override public String cancel(String bookingId) {
                order.add(name);
                return super.cancel(bookingId);
            }
        };
    }

    /** A Service that records every forward call it receives. */
    static SagaLab.Service recordsBook(String name, int capacity, List<String> calls) {
        return new SagaLab.Service(name, capacity) {
            @Override public String book(String bookingId) {
                calls.add(bookingId);
                return super.book(bookingId);
            }
        };
    }

    static class AssertionFailed extends RuntimeException {
        AssertionFailed(String message) { super(message); }
    }

    static void run(String name, Runnable test) {
        try {
            test.run();
            passed++;
            System.out.println("  PASS  " + name);
        } catch (AssertionFailed e) {
            failed++;
            System.out.println("  FAIL  " + name + "  (" + e.getMessage() + ")");
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL  " + name + "  (unexpected " + t + ")");
        }
    }

    static void assertEquals(Object expected, Object actual, String what) {
        if (!expected.equals(actual))
            throw new AssertionFailed(what + ": expected " + expected + ", got " + actual);
    }

    static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionFailed(detail);
    }

    /** The saga must die exactly where the crash was injected. */
    static void assertCrashes(Runnable body) {
        try {
            body.run();
        } catch (SagaLab.Crash e) {
            return;
        }
        throw new AssertionFailed("expected the orchestrator to Crash, it did not");
    }
}
