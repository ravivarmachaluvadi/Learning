/**
 * Executable documentation for structured concurrency: every rule the
 * README states, asserted against the real JDK preview API.
 *
 * Run:  java --enable-preview --source 25 src/ScenarioTests.java
 */
import java.time.Duration;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.FailedException;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.StructuredTaskScope.Subtask;
import java.util.concurrent.StructuredTaskScope.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScenarioTests {

    public static void main(String[] args) {
        run("failing_subtask_cancels_all_its_siblings",
                ScenarioTests::failing_subtask_cancels_all_its_siblings);
        run("no_subtask_outlives_the_scope_even_on_failure",
                ScenarioTests::no_subtask_outlives_the_scope_even_on_failure);
        run("failed_exception_carries_the_original_cause",
                ScenarioTests::failed_exception_carries_the_original_cause);
        run("any_successful_joiner_returns_first_winner_and_cancels_losers",
                ScenarioTests::any_successful_joiner_returns_first_winner_and_cancels_losers);
        run("scope_timeout_cancels_stragglers",
                ScenarioTests::scope_timeout_cancels_stragglers);
        run("await_all_joiner_does_not_fail_fast",
                ScenarioTests::await_all_joiner_does_not_fail_fast);
        run("reading_a_result_before_join_is_rejected",
                ScenarioTests::reading_a_result_before_join_is_rejected);
        run("forking_after_join_is_rejected",
                ScenarioTests::forking_after_join_is_rejected);
        run("only_the_owner_thread_may_fork_into_a_scope",
                ScenarioTests::only_the_owner_thread_may_fork_into_a_scope);
        run("forgetting_join_is_caught_at_close",
                ScenarioTests::forgetting_join_is_caught_at_close);
        run("scoped_values_flow_into_subtasks",
                ScenarioTests::scoped_values_flow_into_subtasks);

        System.out.printf("%n%d passed, %d failed.%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    static void failing_subtask_cancels_all_its_siblings() {
        long t0 = System.nanoTime();
        AtomicBoolean siblingInterrupted = new AtomicBoolean(false);
        try (var scope = StructuredTaskScope.open()) {
            scope.fork(() -> {
                try { Thread.sleep(5_000); }
                catch (InterruptedException e) { siblingInterrupted.set(true); throw e; }
                return "slow";
            });
            scope.fork(() -> { Thread.sleep(30); throw new RuntimeException("boom"); });
            scope.join();
            fail("join() should have thrown");
        } catch (FailedException | InterruptedException expected) { }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(ms < 1_000, "took " + ms + " ms — the 5s sibling was awaited instead of cancelled");
        assertThat(siblingInterrupted.get(), "slow sibling was never interrupted");
    }

    static void no_subtask_outlives_the_scope_even_on_failure() {
        AtomicBoolean cleanupRan = new AtomicBoolean(false);
        try (var scope = StructuredTaskScope.open()) {
            scope.fork(() -> {
                try { Thread.sleep(5_000); return "slow"; }
                finally { cleanupRan.set(true); }        // must have run before the scope exits
            });
            scope.fork(() -> { throw new RuntimeException("boom"); });
            scope.join();
        } catch (FailedException | InterruptedException expected) { }
        // The try-with-resources has fully exited here; close() waited for the interrupted sibling.
        assertThat(cleanupRan.get(), "scope exited while a subtask was still running (finally had not run)");
    }

    static void failed_exception_carries_the_original_cause() {
        try (var scope = StructuredTaskScope.open()) {
            scope.fork(() -> { throw new IllegalStateException("db connection lost"); });
            scope.join();
            fail("join() should have thrown");
        } catch (FailedException e) {
            assertThat(e.getCause() instanceof IllegalStateException
                            && e.getCause().getMessage().equals("db connection lost"),
                    "cause was " + e.getCause());
        } catch (InterruptedException e) { fail("unexpected interrupt"); }
    }

    static void any_successful_joiner_returns_first_winner_and_cancels_losers() throws Exception {
        long t0 = System.nanoTime();
        try (var scope = StructuredTaskScope.open(Joiner.<String>anySuccessfulResultOrThrow())) {
            scope.fork(() -> { Thread.sleep(500); return "slow-replica"; });
            scope.fork(() -> { Thread.sleep(20);  return "fast-replica"; });
            String winner = scope.join();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertThat(winner.equals("fast-replica"), "winner was " + winner);
            assertThat(ms < 400, "took " + ms + " ms — the losing replica was awaited instead of cancelled");
        }
    }

    static void scope_timeout_cancels_stragglers() {
        long t0 = System.nanoTime();
        try (var scope = StructuredTaskScope.open(Joiner.awaitAllSuccessfulOrThrow(),
                cfg -> cfg.withTimeout(Duration.ofMillis(120)))) {
            scope.fork(() -> { Thread.sleep(2_000); return "too slow"; });
            scope.join();
            fail("join() should have timed out");
        } catch (TimeoutException e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertThat(ms >= 100 && ms < 1_000, "deadline fired at " + ms + " ms, expected ~120");
        } catch (InterruptedException e) { fail("unexpected interrupt"); }
    }

    static void await_all_joiner_does_not_fail_fast() throws Exception {
        // Joiner.awaitAll(): failures do NOT cancel siblings and join() does not throw —
        // you inspect each subtask yourself. (Useful for "best effort, gather what you can".)
        AtomicBoolean slowFinishedNormally = new AtomicBoolean(false);
        Subtask<String> slow;
        try (var scope = StructuredTaskScope.open(Joiner.<String>awaitAll())) {
            slow = scope.fork(() -> { Thread.sleep(150); slowFinishedNormally.set(true); return "ok"; });
            scope.fork(() -> { throw new RuntimeException("one bad apple"); });
            scope.join();                                    // no exception!
            assertThat(slow.state() == Subtask.State.SUCCESS, "slow subtask state: " + slow.state());
        }
        assertThat(slowFinishedNormally.get(), "awaitAll cancelled a sibling on failure — it must not");
    }

    static void reading_a_result_before_join_is_rejected() throws Exception {
        try (var scope = StructuredTaskScope.open()) {
            var subtask = scope.fork(() -> "hello");
            boolean threw = false;
            try { subtask.get(); } catch (IllegalStateException e) { threw = true; }
            assertThat(threw, "get() before join() should throw IllegalStateException");
            scope.join();
        }
    }

    static void forking_after_join_is_rejected() throws Exception {
        try (var scope = StructuredTaskScope.open()) {
            scope.fork(() -> "a");
            scope.join();
            boolean threw = false;
            try { scope.fork(() -> "late child"); } catch (IllegalStateException e) { threw = true; }
            assertThat(threw, "fork() after join() should throw IllegalStateException");
        }
    }

    static void only_the_owner_thread_may_fork_into_a_scope() throws Exception {
        AtomicBoolean rejected = new AtomicBoolean(false);
        try (var scope = StructuredTaskScope.open()) {
            scope.fork(() -> {
                try { scope.fork(() -> "grandchild-in-parents-scope"); }
                catch (RuntimeException e) { rejected.set(true); }
                return "ok";
            });
            scope.join();
        }
        assertThat(rejected.get(), "a subtask was allowed to fork into its parent's scope");
    }

    static void forgetting_join_is_caught_at_close() {
        boolean threw = false;
        try {
            try (var scope = StructuredTaskScope.open()) {
                scope.fork(() -> "started but never joined");
            }                                            // close() notices the missing join()
        } catch (IllegalStateException e) { threw = true; }
        assertThat(threw, "closing a scope without join() should throw IllegalStateException");
    }

    static final ScopedValue<String> REQUEST_ID = ScopedValue.newInstance();

    static void scoped_values_flow_into_subtasks() {
        // ScopedValue (final in JDK 25) is the ThreadLocal successor; bindings are
        // automatically visible in subtasks forked inside the bound scope.
        String seen = ScopedValue.where(REQUEST_ID, "req-42").call(() -> {
            try (var scope = StructuredTaskScope.<String>open()) {
                var st = scope.fork(() -> REQUEST_ID.get());     // child reads parent's binding
                scope.join();
                return st.get();
            } catch (InterruptedException e) { throw new RuntimeException(e); }
        });
        assertThat("req-42".equals(seen), "subtask saw '" + seen + "' instead of the parent's binding");
    }

    // ---- tiny harness ----------------------------------------------------
    interface Scenario { void run() throws Exception; }
    static int passed, failed;

    static void run(String name, Scenario s) {
        try { s.run(); passed++; System.out.println("PASS  " + name); }
        catch (Throwable e) { failed++; System.out.println("FAIL  " + name + "\n      " + e); }
    }
    static void assertThat(boolean cond, String msgIfFalse) { if (!cond) throw new AssertionError(msgIfFalse); }
    static void fail(String msg) { throw new AssertionError(msg); }
}
