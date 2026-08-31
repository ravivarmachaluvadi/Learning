/**
 * Executable documentation: each test is a scenario from README.md.
 *
 * Zero-dependency test harness (no JUnit needed). Run with ONE carrier so the
 * scheduling claims are actually exercised, not accidentally true:
 *
 *   java -Djdk.virtualThreadScheduler.parallelism=1 src/ScenarioTests.java
 */
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ScenarioTests {

    public static void main(String[] args) throws Exception {
        run("twenty_thousand_virtual_threads_all_complete_on_one_carrier",
                ScenarioTests::twenty_thousand_virtual_threads_all_complete_on_one_carrier);
        run("blocked_virtual_thread_releases_its_carrier_to_others",
                ScenarioTests::blocked_virtual_thread_releases_its_carrier_to_others);
        run("sleeping_inside_synchronized_does_not_pin_on_this_jdk",
                ScenarioTests::sleeping_inside_synchronized_does_not_pin_on_this_jdk);
        run("thread_identity_is_stable_across_unmount_and_remount",
                ScenarioTests::thread_identity_is_stable_across_unmount_and_remount);
        run("thread_locals_stay_isolated_per_virtual_thread",
                ScenarioTests::thread_locals_stay_isolated_per_virtual_thread);
        run("executor_close_waits_for_submitted_tasks",
                ScenarioTests::executor_close_waits_for_submitted_tasks);
        run("interruption_works_exactly_like_platform_threads",
                ScenarioTests::interruption_works_exactly_like_platform_threads);
        run("virtual_threads_are_always_daemon_threads",
                ScenarioTests::virtual_threads_are_always_daemon_threads);

        System.out.printf("%n%d passed, %d failed.%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // Scenario: creating tens of thousands of threads is a normal, cheap act.
    static void twenty_thousand_virtual_threads_all_complete_on_one_carrier() throws Exception {
        AtomicInteger done = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            threads.add(Thread.ofVirtual().start(() -> { sleep(10); done.incrementAndGet(); }));
        }
        for (Thread t : threads) t.join();
        assertThat(done.get() == 20_000, "expected 20,000 completions, got " + done.get());
    }

    // Scenario: while thread A is blocked, thread B runs on the SAME single carrier.
    static void blocked_virtual_thread_releases_its_carrier_to_others() throws Exception {
        AtomicBoolean bFinishedWhileASlept = new AtomicBoolean(false);
        CountDownLatch aIsAsleep = new CountDownLatch(1);
        Thread a = Thread.ofVirtual().start(() -> { aIsAsleep.countDown(); sleep(300); });
        Thread b = Thread.ofVirtual().start(() -> {
            await(aIsAsleep);
            // If A had kept the only carrier while sleeping, we could never run here.
            bFinishedWhileASlept.set(a.isAlive());
        });
        b.join(); a.join();
        assertThat(bFinishedWhileASlept.get(), "B could not run while A was blocked — carrier was not released");
    }

    // Scenario: JEP 491 (JDK 24+). Four sleeps inside synchronized overlap even with one carrier.
    static void sleeping_inside_synchronized_does_not_pin_on_this_jdk() throws Exception {
        long t0 = System.nanoTime();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Object lock = new Object();
            threads.add(Thread.ofVirtual().start(() -> { synchronized (lock) { sleep(150); } }));
        }
        for (Thread t : threads) t.join();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(ms < 450, "sleeps serialized (" + ms + " ms) — synchronized pinned the carrier (pre-JDK-24 behavior)");
    }

    // Scenario: unmounting is invisible — it is the same Thread object before and after.
    static void thread_identity_is_stable_across_unmount_and_remount() throws Exception {
        AtomicBoolean same = new AtomicBoolean();
        Thread t = Thread.ofVirtual().name("identity-check").start(() -> {
            Thread before = Thread.currentThread();
            long id = before.threadId();
            sleep(50);   // unmount + remount happens in here
            same.set(Thread.currentThread() == before
                    && Thread.currentThread().threadId() == id
                    && Thread.currentThread().getName().equals("identity-check"));
        });
        t.join();
        assertThat(same.get(), "thread identity changed across a blocking call");
    }

    // Scenario: ThreadLocal works normally — each of 200 threads sees only its own value.
    static void thread_locals_stay_isolated_per_virtual_thread() throws Exception {
        ThreadLocal<Integer> local = new ThreadLocal<>();
        AtomicInteger violations = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            int mine = i;
            threads.add(Thread.ofVirtual().start(() -> {
                local.set(mine);
                sleep(20);   // unmount; another thread will use this carrier meanwhile
                if (local.get() != mine) violations.incrementAndGet();
            }));
        }
        for (Thread t : threads) t.join();
        assertThat(violations.get() == 0, violations.get() + " threads saw another thread's ThreadLocal value");
    }

    // Scenario: try-with-resources on the executor is the shutdown idiom — close() waits.
    static void executor_close_waits_for_submitted_tasks() {
        AtomicBoolean taskRan = new AtomicBoolean(false);
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            ex.submit(() -> { sleep(100); taskRan.set(true); });
        }   // close() blocks here until the task is done
        assertThat(taskRan.get(), "close() returned before the submitted task finished");
    }

    // Scenario: interrupt() wakes a blocked virtual thread with InterruptedException, as always.
    static void interruption_works_exactly_like_platform_threads() throws Exception {
        AtomicBoolean interrupted = new AtomicBoolean(false);
        CountDownLatch sleeping = new CountDownLatch(1);
        Thread t = Thread.ofVirtual().start(() -> {
            try { sleeping.countDown(); Thread.sleep(60_000); }
            catch (InterruptedException e) { interrupted.set(true); }
        });
        await(sleeping);
        sleep(20);          // give it a moment to actually park
        t.interrupt();
        t.join(2_000);
        assertThat(interrupted.get(), "interrupt() did not wake the blocked virtual thread");
    }

    // Scenario: a virtual thread cannot keep the JVM alive, and you cannot make it try.
    static void virtual_threads_are_always_daemon_threads() throws Exception {
        Thread t = Thread.ofVirtual().unstarted(() -> {});
        assertThat(t.isDaemon(), "virtual thread reported isDaemon() == false");
        boolean threw = false;
        try { t.setDaemon(false); } catch (IllegalArgumentException e) { threw = true; }
        assertThat(threw, "setDaemon(false) should be rejected on a virtual thread");
    }

    // ---- tiny harness ----------------------------------------------------
    interface Scenario { void run() throws Exception; }
    static int passed, failed;

    static void run(String name, Scenario s) {
        try { s.run(); passed++; System.out.println("PASS  " + name); }
        catch (Throwable e) { failed++; System.out.println("FAIL  " + name + "\n      " + e.getMessage()); }
    }

    static void assertThat(boolean condition, String messageIfFalse) {
        if (!condition) throw new AssertionError(messageIfFalse);
    }
    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
