import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Executable documentation: each test name is a scenario from README.md.
 *
 * Run (from this directory, so ../lib resolves):
 *   java -cp ../lib/h2.jar OutboxTest.java
 *
 * Every test gets its own pair of H2 files, deleted before and after it runs,
 * so a test starts from an empty database exactly as the Python lab's
 * temporary directory did.
 */
public class OutboxTest {

    static int passed = 0, failed = 0;

    static String ordersDb, invDb;
    static OutboxLab.Broker broker;
    /** Everything opened during one test, so tearDown can release the files. */
    static final List<AutoCloseable> opened = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("Running OutboxTest — each test name is a scenario from README.md");
        System.out.println();

        // ------------------------------------------------------------------
        // The problem
        // ------------------------------------------------------------------
        run("test_dual_write_loses_message_when_crash_after_db_commit", () -> {
            var svc = orderService();
            assertCrash(() -> svc.placeOrderDualWrite(broker, "red-mug", 2, true));
            var restarted = orderService();                     // process restart
            assertEquals(1, restarted.orders().size(), "order was committed");
            assertEquals(0, broker.log.size(), "event silently lost");
        });

        run("test_dual_write_works_only_when_nothing_crashes", () -> {
            var svc = orderService();
            svc.placeOrderDualWrite(broker, "red-mug", 2);
            assertEquals(1, svc.orders().size(), "order saved");
            assertEquals(1, broker.log.size(), "event published");
        });

        // ------------------------------------------------------------------
        // The pattern
        // ------------------------------------------------------------------
        run("test_outbox_write_is_atomic_with_business_write", () -> {
            var svc = orderService();
            svc.placeOrderOutbox("red-mug", 2);
            assertEquals(1, svc.orders().size(), "order saved");
            assertEquals(1, svc.unpublishedOutboxRows().size(), "outbox row saved with it");
        });

        run("test_message_survives_crash_before_relay_runs", () -> {
            var svc = orderService();
            svc.placeOrderOutbox("red-mug", 2);
            // producer process "crashes" here; relay starts later, fresh
            var relay = relay();
            assertEquals(1, relay.pollOnce(broker), "relay published the pending row");
            assertEquals(1, broker.log.size(), "message reached the broker");
            var restarted = orderService();
            assertEquals(0, restarted.unpublishedOutboxRows().size(), "row is marked published");
        });

        run("test_relay_poll_is_noop_when_outbox_empty", () -> {
            orderService();                                     // creates the tables
            var relay = relay();
            assertEquals(0, relay.pollOnce(broker), "nothing to send");
        });

        run("test_relay_preserves_commit_order", () -> {
            var svc = orderService();
            svc.placeOrderOutbox("red-mug", 1);
            svc.placeOrderOutbox("blue-mug", 2);
            svc.placeOrderOutbox("green-mug", 3);
            relay().pollOnce(broker);
            List<String> items = broker.log.stream().map(OutboxLab.Message::payload).toList();
            assertContains("red-mug", items.get(0), "first committed, first published");
            assertContains("blue-mug", items.get(1), "second committed, second published");
            assertContains("green-mug", items.get(2), "third committed, third published");
        });

        // ------------------------------------------------------------------
        // The price: at-least-once
        // ------------------------------------------------------------------
        run("test_relay_crash_between_publish_and_mark_causes_duplicate", () -> {
            var svc = orderService();
            svc.placeOrderOutbox("red-mug", 2);
            var crashing = relay();
            assertCrash(() -> crashing.pollOnce(broker, true));
            relay().pollOnce(broker);                           // relay restart
            assertEquals(2, broker.log.size(), "same message twice");
            assertEquals(broker.log.get(0).messageId(), broker.log.get(1).messageId(),
                    "and it really is the same message_id");
        });

        run("test_naive_consumer_corrupts_data_on_duplicate", () -> {
            var svc = orderService();
            svc.placeOrderOutbox("red-mug", 2);
            var crashing = relay();
            assertCrash(() -> crashing.pollOnce(broker, true));
            relay().pollOnce(broker);
            var inv = inventory(false);
            inv.setStock("red-mug", 10);
            inv.consumeAll(broker);
            assertEquals(6, inv.stock("red-mug"), "decremented twice: wrong");
        });

        run("test_idempotent_consumer_survives_duplicate", () -> {
            var svc = orderService();
            svc.placeOrderOutbox("red-mug", 2);
            var crashing = relay();
            assertCrash(() -> crashing.pollOnce(broker, true));
            relay().pollOnce(broker);
            var inv = inventory(true);
            inv.setStock("red-mug", 10);
            var consumed = inv.consumeAll(broker);
            assertEquals(1, consumed.applied(), "one message applied");
            assertEquals(1, consumed.skipped(), "one duplicate skipped");
            assertEquals(8, inv.stock("red-mug"), "duplicate deduped: correct");
        });

        run("test_idempotent_consumer_resumes_after_its_own_restart", () -> {
            var svc = orderService();
            svc.placeOrderOutbox("red-mug", 2);
            svc.placeOrderOutbox("red-mug", 3);
            relay().pollOnce(broker);
            var inv = inventory(true);
            inv.setStock("red-mug", 10);
            inv.consumeAll(broker);
            // consumer restarts over the same durable state, re-reads the log
            var inv2 = inventory(true);
            var consumed = inv2.consumeAll(broker);
            assertEquals(0, consumed.applied(), "offset was durable; nothing re-applied");
            assertEquals(5, inv2.stock("red-mug"), "stock untouched by the restart");
        });

        System.out.println();
        System.out.printf("%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ runner

    /** A test body, allowed to throw the way the lab's own methods do. */
    interface Body {
        void run() throws Exception;
    }

    static void run(String name, Body body) {
        setUp(name);
        try {
            body.run();
            passed++;
            System.out.println("  PASS  " + name);
        } catch (AssertionError e) {
            failed++;
            System.out.println("  FAIL  " + name + "  (" + e.getMessage() + ")");
        } catch (Exception e) {
            failed++;
            System.out.println("  FAIL  " + name + "  (" + e + ")");
        } finally {
            tearDown();
        }
    }

    static void setUp(String name) {
        String base = "./.lab-test-" + name;
        ordersDb = base + "-orders";
        invDb = base + "-inventory";
        deleteFiles();
        broker = new OutboxLab.Broker();
        opened.clear();
    }

    static void tearDown() {
        OutboxLab.closeAll(opened.toArray(new AutoCloseable[0]));
        opened.clear();
        deleteFiles();
    }

    static void deleteFiles() {
        for (String path : new String[]{ordersDb, invDb}) {
            new File(path + ".mv.db").delete();
            new File(path + ".trace.db").delete();
        }
    }

    // ----------------------------------------------------------------- actors

    static OutboxLab.OrderService orderService() throws SQLException {
        return register(new OutboxLab.OrderService(ordersDb));
    }

    static OutboxLab.Relay relay() throws SQLException {
        return register(new OutboxLab.Relay(ordersDb));
    }

    static OutboxLab.InventoryService inventory(boolean idempotent) throws SQLException {
        return register(new OutboxLab.InventoryService(invDb, idempotent));
    }

    static <T extends AutoCloseable> T register(T resource) {
        opened.add(resource);
        return resource;
    }

    // ------------------------------------------------------------- assertions

    static void assertEquals(Object expected, Object actual, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + ", got " + actual);
        }
    }

    static void assertContains(String needle, String haystack, String what) {
        if (!haystack.contains(needle)) {
            throw new AssertionError(what + ": expected " + haystack + " to contain " + needle);
        }
    }

    /** The lab's crash injection must actually fire — that is the scenario. */
    static void assertCrash(Body body) {
        try {
            body.run();
        } catch (OutboxLab.Crash expected) {
            return;
        } catch (Exception e) {
            throw new AssertionError("expected a Crash, got " + e);
        }
        throw new AssertionError("expected a Crash, nothing was thrown");
    }
}
