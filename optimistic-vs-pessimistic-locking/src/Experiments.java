import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Measured experiments. Run all:   java -cp ../lib/h2.jar src/Experiments.java
 * Run one:  java -cp ../lib/h2.jar src/Experiments.java crossover
 *
 * Names: overwrite | crossover | waste | lunch | queue | deadlock | starvation
 */
public class Experiments {

    public static void main(String[] args) throws Exception {
        Set<String> want = args.length == 0 ? null : Set.of(args);
        if (want == null || want.contains("overwrite"))  overwrite();
        if (want == null || want.contains("crossover"))  crossover();
        if (want == null || want.contains("waste"))      waste();
        if (want == null || want.contains("lunch"))      lunch();
        if (want == null || want.contains("queue"))      queue();
        if (want == null || want.contains("deadlock"))   deadlock();
        if (want == null || want.contains("starvation")) starvation();
    }

    static void header(String title) {
        System.out.println();
        System.out.println("=== " + title);
    }

    /**
     * EXPERIMENT 1 — how often does the naive form edit actually lose data?
     * 100 rounds of: Meera reads, Sam reads, Meera saves, Sam saves.
     * Then the same 100 rounds with the version check + reload-merge retry.
     */
    static void overwrite() throws Exception {
        header("overwrite — the silent kill rate of last-save-wins");
        int naiveLost = 0, optLost = 0, optConflicts = 0;
        for (int r = 0; r < 100; r++) {
            final int round = r;
            String db = Db.freshDb("ow");
            Db.productSchema(db);
            try (Connection meera = Db.connect(db); Connection sam = Db.connect(db)) {
                Store.Product m = Store.read(meera, 42); meera.commit();
                Store.Product s = Store.read(sam, 42);   sam.commit();
                Store.naiveSave(meera, new Store.Product(42, "desc by Meera r" + round, m.price(), 0));
                meera.commit();
                Store.naiveSave(sam, new Store.Product(42, s.description(), 1200, 0));
                sam.commit();
                if (!Store.read(sam, 42).description().contains("Meera")) naiveLost++;
                sam.commit();
            }
            String db2 = Db.freshDb("ow2");
            Db.productSchema(db2);
            try (Connection meera = Db.connect(db2); Connection sam = Db.connect(db2)) {
                Store.Product sSees = Store.read(sam, 42); sam.commit(); // Sam's form snapshot
                Store.optimisticUpdate(meera, 42,
                    p -> new Store.Product(42, "desc by Meera r" + round, p.price(), p.version()));
                try { // Sam saves from his now-stale form
                    Store.optimisticSave(sam, new Store.Product(42, sSees.description(), 1200, sSees.version()));
                    sam.commit();
                } catch (Store.StaleVersionException e) {
                    sam.rollback();
                    optConflicts++; // detected -> re-read and re-apply only his price edit
                    Store.optimisticUpdate(sam, 42,
                        p -> new Store.Product(42, p.description(), 1200, p.version()));
                }
                Store.Product fin = Store.read(sam, 42); sam.commit();
                if (!(fin.description().contains("Meera") && fin.price() == 1200)) optLost++;
            }
        }
        System.out.println("naive last-save-wins:  Meera's edit destroyed in " + naiveLost + "/100 rounds (0 errors shown to anyone)");
        System.out.println("optimistic + retry:    edits lost " + optLost + "/100 rounds, conflicts detected " + optConflicts + "/100, each re-read and merged");
    }

    record RaceResult(long ms, int retries, int total) {}

    /** T clients, each doing R increments spread over `rows` rows with
     *  `workMs` of CPU-bound business logic between read and write. */
    static RaceResult race(boolean optimistic, int threads, int perThread,
                           int rows, int workMs) throws Exception {
        String db = Db.freshDb("race");
        Db.counterSchema(db, rows);
        AtomicInteger retries = new AtomicInteger();
        long t0 = System.currentTimeMillis();
        Db.race(threads, i -> {
            Random rnd = new Random(42 * 1000 + i);
            try (Connection c = Db.connect(db)) {
                c.createStatement().execute("SET LOCK_TIMEOUT 60000");
                for (int k = 0; k < perThread; k++) {
                    int row = rnd.nextInt(rows);
                    if (optimistic) retries.addAndGet(Store.optimisticIncrement(c, row, workMs));
                    else            Store.pessimisticIncrement(c, row, workMs);
                }
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
        long ms = System.currentTimeMillis() - t0;
        int total;
        try (Connection c = Db.connect(db)) { total = Db.queryInt(c, "SELECT SUM(n) FROM counter"); }
        return new RaceResult(ms, retries.get(), total);
    }

    /**
     * EXPERIMENT 2 — the crossover. Same work (8 clients x 25 updates,
     * 2ms of business logic each), only the NUMBER OF HOT ROWS varies.
     * Contention is a property of the data-access pattern; watch it decide
     * which strategy wins.
     */
    static void crossover() throws Exception {
        header("crossover — contention picks the winner (8 clients x 25 ops, 2ms work)");
        System.out.printf("%6s | %34s | %14s | %s%n", "rows", "OPTIMISTIC", "PESSIMISTIC", "faster wall clock");
        race(true, 8, 25, 1, 2); race(false, 8, 25, 1, 2); // JIT+cache warmup
        for (int rows : new int[]{1, 2, 4, 8, 32}) {
            RaceResult opt = race(true, 8, 25, rows, 2);
            RaceResult pes = race(false, 8, 25, rows, 2);
            String w = opt.ms() < pes.ms() * 0.9 ? "optimistic"
                     : pes.ms() < opt.ms() * 0.9 ? "pessimistic" : "~tie";
            // useful CPU = 200 ops x 2ms; every retry re-burned another 2ms
            System.out.printf("%6d | %6dms %4d retries %4dms burned | %8dms 0ms | %s   [both exact: %d=%d]%n",
                rows, opt.ms(), opt.retries(), 400 + opt.retries() * 2, pes.ms(), w, opt.total(), pes.total());
        }
        System.out.println("('burned' = CPU spent incl. redone work; useful work is 400ms. Wall clock can");
        System.out.println(" tie on an idle multi-core box because SPARE cores absorb the redone work —");
        System.out.println(" production boxes don't have spare cores at peak, which is when contention peaks.)");
    }

    /**
     * EXPERIMENT 3 — what optimistic conflict actually costs: the work is
     * REDONE. One hot row, 8 clients, business logic grows from 0 to 15ms.
     * Wasted work = retries x workMs — work performed, then thrown away.
     */
    static void waste() throws Exception {
        header("waste — a retry re-runs the business logic (1 hot row, 8 clients x 10 ops)");
        System.out.printf("%7s | %30s | %15s%n", "work", "OPTIMISTIC", "PESSIMISTIC");
        for (int workMs : new int[]{0, 1, 5, 15}) {
            RaceResult opt = race(true, 8, 10, 1, workMs);
            RaceResult pes = race(false, 8, 10, 1, workMs);
            System.out.printf("%5dms | %6dms %4d retries = ~%4dms redone | %8dms%n",
                workMs, opt.ms(), opt.retries(), opt.retries() * workMs, pes.ms());
        }
        System.out.println("(pessimistic serial floor: 8x10 x work; optimistic pays that AND the redone work)");
    }

    /**
     * EXPERIMENT 4 — the lunch break. One client pauses 2s mid-update
     * (a slow downstream call, a GC pause, a user on the phone). Five other
     * clients keep trying to update the same row for those 2 seconds.
     */
    static void lunch() throws Exception {
        header("lunch — who pays for one client's 2s pause?");
        for (boolean pessimistic : new boolean[]{true, false}) {
            String db = Db.freshDb("lunch");
            Db.productSchema(db);
            long pauseMs = 2000;
            CountDownLatch started = new CountDownLatch(1);
            AtomicInteger fastSaves = new AtomicInteger(), timeouts = new AtomicInteger();
            AtomicBoolean stop = new AtomicBoolean(false);
            String slowOutcome;
            Thread slow = new Thread(() -> {
                try (Connection c = Db.connect(db)) {
                    if (pessimistic) {
                        Store.Product p = Store.readForUpdate(c, 42);
                        started.countDown();
                        Db.sleep(pauseMs);
                        Store.naiveSave(c, new Store.Product(42, p.description(), 1111, 0));
                        c.commit();
                    } else {
                        Store.Product p = Store.read(c, 42); c.commit();
                        started.countDown();
                        Db.sleep(pauseMs);
                        try {
                            Store.optimisticSave(c, new Store.Product(42, p.description(), 1111, p.version()));
                            c.commit();
                        } catch (Store.StaleVersionException e) { c.rollback(); }
                    }
                } catch (SQLException e) { throw new RuntimeException(e); }
            });
            slow.start(); started.await();
            final long windowEnd = System.currentTimeMillis() + pauseMs - 200; // strictly inside the pause
            Thread[] fast = new Thread[5];
            for (int i = 0; i < 5; i++) {
                final int id = i;
                fast[i] = new Thread(() -> {
                    try (Connection c = Db.connect(db)) {
                        c.createStatement().execute("SET LOCK_TIMEOUT 500"); // a sane request budget
                        while (!stop.get()) {
                            try {
                                Store.optimisticUpdate(c, 42,
                                    p -> new Store.Product(42, p.description(), 2000 + id, p.version()));
                                if (System.currentTimeMillis() < windowEnd) fastSaves.incrementAndGet();
                            } catch (SQLException | RuntimeException e) {
                                // even an optimistic writer's UPDATE must briefly take the row
                                // lock — against a holder who never lets go, it times out
                                if (System.currentTimeMillis() < windowEnd) timeouts.incrementAndGet();
                                try { c.rollback(); } catch (SQLException ignored) {}
                            }
                            Db.sleep(50); // ~request rate
                        }
                    } catch (SQLException e) { throw new RuntimeException(e); }
                });
                fast[i].start();
            }
            Db.sleep(pauseMs - 200);   // measure strictly inside the pause
            stop.set(true);
            for (Thread t : fast) t.join();
            slow.join();
            int finalPrice;
            try (Connection c = Db.connect(db)) { finalPrice = Db.queryInt(c, "SELECT price FROM product WHERE id=42"); }
            slowOutcome = pessimistic
                ? "sleeper's save landed; the queued waiters piled through only AFTER its commit"
                : (finalPrice == 1111 ? "slow save landed" : "sleeper's own save CONFLICTED (only the sleeper pays)");
            System.out.printf("%-11s: other clients landed %3d saves, hit %2d lock timeouts during the pause | %s%n",
                pessimistic ? "PESSIMISTIC" : "OPTIMISTIC", fastSaves.get(), timeouts.get(), slowOutcome);
        }
    }

    /**
     * EXPERIMENT 5 — the job queue. 120 jobs x 10ms of processing, 4 workers.
     * Claiming strategy is everything:
     *   plain FOR UPDATE       -> every worker queues on the SAME first row
     *   FOR UPDATE SKIP LOCKED -> each worker locks a different row
     *   optimistic claim       -> UPDATE ... WHERE status='PENDING', count=1 means "mine"
     */
    static void queue() throws Exception {
        header("queue — 120 jobs x 10ms, 4 workers");
        for (String mode : new String[]{"for-update", "skip-locked", "optimistic-claim"}) {
            String db = Db.freshDb("q");
            Db.jobSchema(db, 120);
            AtomicInteger claimMisses = new AtomicInteger();
            long t0 = System.currentTimeMillis();
            Db.race(4, w -> {
                try (Connection c = Db.connect(db)) {
                    c.createStatement().execute("SET LOCK_TIMEOUT 60000");
                    while (true) {
                        Integer jobId = null;
                        switch (mode) {
                            case "for-update", "skip-locked" -> {
                                String suffix = mode.equals("skip-locked") ? " SKIP LOCKED" : "";
                                try (ResultSet r = c.createStatement().executeQuery(
                                        "SELECT id FROM job WHERE status='PENDING' " +
                                        "ORDER BY id LIMIT 1 FOR UPDATE" + suffix)) {
                                    if (r.next()) jobId = r.getInt(1);
                                }
                                // row is locked until commit: process INSIDE the claim txn
                                if (jobId != null) {
                                    Db.compute(10);
                                    c.createStatement().executeUpdate(
                                        "UPDATE job SET status='DONE', worker='w" + w + "' WHERE id=" + jobId);
                                }
                                c.commit();
                            }
                            case "optimistic-claim" -> {
                                try (ResultSet r = c.createStatement().executeQuery(
                                        "SELECT id FROM job WHERE status='PENDING' ORDER BY id LIMIT 1")) {
                                    if (r.next()) jobId = r.getInt(1);
                                }
                                if (jobId != null) {
                                    // status IS the version: PENDING -> RUNNING claims it
                                    int n = c.createStatement().executeUpdate(
                                        "UPDATE job SET status='RUNNING', worker='w" + w +
                                        "' WHERE id=" + jobId + " AND status='PENDING'");
                                    c.commit(); // claim is visible; lock held only for microseconds
                                    if (n == 0) { claimMisses.incrementAndGet(); continue; }
                                    Db.compute(10); // process OUTSIDE any lock
                                    c.createStatement().executeUpdate(
                                        "UPDATE job SET status='DONE' WHERE id=" + jobId);
                                    c.commit();
                                } else c.commit();
                            }
                        }
                        if (jobId == null) break;
                    }
                } catch (SQLException e) { throw new RuntimeException(e); }
            });
            long ms = System.currentTimeMillis() - t0;
            try (Connection c = Db.connect(db)) {
                int done = Db.queryInt(c, "SELECT COUNT(*) FROM job WHERE status='DONE'");
                // which workers actually got jobs?
                Map<String, Integer> byWorker = new TreeMap<>();
                try (ResultSet r = c.createStatement().executeQuery(
                        "SELECT worker, COUNT(*) FROM job GROUP BY worker ORDER BY worker")) {
                    while (r.next()) byWorker.put(r.getString(1), r.getInt(2));
                }
                System.out.printf("%-16s: %5dms, %d/120 done, per-worker %s%s%n",
                    mode, ms, done, byWorker,
                    mode.equals("optimistic-claim") ? ", claim races lost: " + claimMisses : "");
            }
        }
        System.out.println("(serial floor 1200ms, perfect 4-way split 300ms)");
    }

    /**
     * EXPERIMENT 6 — deadlocks are a pessimistic-only failure mode.
     * 2 clients x 30 transfers between the same two accounts, each locking
     * the two rows in OPPOSITE order.
     */
    static void deadlock() throws Exception {
        header("deadlock — opposite-order transfers, 2 clients x 30 rounds");
        // pessimistic, random order
        String db = Db.freshDb("dl");
        Db.counterSchema(db, 2);
        AtomicInteger victims = new AtomicInteger(), pesRetries = new AtomicInteger();
        Db.race(2, i -> {
            try (Connection c = Db.connect(db)) {
                c.createStatement().execute("SET LOCK_TIMEOUT 8000");
                for (int k = 0; k < 30; k++) {
                    int first = i, second = 1 - i;
                    while (true) {
                        try {
                            c.createStatement().executeQuery("SELECT n FROM counter WHERE id=" + first + " FOR UPDATE");
                            c.createStatement().executeQuery("SELECT n FROM counter WHERE id=" + second + " FOR UPDATE");
                            c.createStatement().executeUpdate("UPDATE counter SET n=n+1 WHERE id=" + first);
                            c.createStatement().executeUpdate("UPDATE counter SET n=n-1 WHERE id=" + second);
                            c.commit();
                            break;
                        } catch (SQLException e) {
                            if ("40001".equals(e.getSQLState())) { victims.incrementAndGet(); pesRetries.incrementAndGet(); }
                            else throw new RuntimeException(e);
                            c.rollback();
                        }
                    }
                }
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
        // optimistic, same opposite orders
        String db2 = Db.freshDb("dl2");
        Db.counterSchema(db2, 2);
        AtomicInteger optRetries = new AtomicInteger(), optDeadlocks = new AtomicInteger();
        Db.race(2, i -> {
            try (Connection c = Db.connect(db2)) {
                c.createStatement().execute("SET LOCK_TIMEOUT 8000");
                for (int k = 0; k < 30; k++) {
                    try {
                        optRetries.addAndGet(Store.optimisticIncrement(c, i, 0));
                        optRetries.addAndGet(Store.optimisticIncrement(c, 1 - i, 0));
                    } catch (SQLException e) {
                        if ("40001".equals(e.getSQLState())) optDeadlocks.incrementAndGet();
                        else throw new RuntimeException(e);
                    }
                }
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
        System.out.println("pessimistic (opposite lock order): " + victims + " deadlock victims (SQLState 40001), each aborted+retried");
        System.out.println("optimistic  (same access pattern): " + optDeadlocks + " deadlocks, " + optRetries + " version-conflict retries");
        System.out.println("(nobody ever WAITS while HOLDING under optimistic — the cycle cannot form)");
    }

    /**
     * EXPERIMENT 7 — starvation: optimistic locking has no queue, so a SLOW
     * transaction can lose the race forever to fast neighbours. One client
     * needs 30ms to decide; 6 clients decide in ~0ms, updating constantly.
     * Then the standard fix: give up being optimistic after 3 losses and
     * take the lock (escalation).
     */
    /** One optimistic attempt: read, think `workMs`, conditional write.
     *  Returns true if the save landed. */
    static boolean attempt(Connection c, int workMs) throws SQLException {
        int n; long v;
        try (ResultSet r = c.createStatement().executeQuery(
                "SELECT n, version FROM counter WHERE id=0")) { r.next(); n = r.getInt(1); v = r.getLong(2); }
        Db.compute(workMs);
        int cnt = c.createStatement().executeUpdate(
            "UPDATE counter SET n=" + (n + 1) + ", version=version+1 WHERE id=0 AND version=" + v);
        if (cnt == 1) { c.commit(); return true; }
        c.rollback(); return false;
    }

    static void starvation() throws Exception {
        header("starvation — a 30ms thinker vs 6 instant writers on one row (goal: 5 saves, 8s budget)");
        // Optimistic locking has no queue: whoever commits first wins, and a
        // client that needs 30ms to decide almost never commits first against
        // neighbours who decide instantly. Retrying doesn't help — every retry
        // faces the same race. That is starvation (a liveness failure with no
        // error anywhere). The standard fix: stop being optimistic after N
        // losses and take the lock — the lock's QUEUE is what guarantees a turn.
        for (boolean escalate : new boolean[]{false, true}) {
            String db = Db.freshDb("starve");
            Db.counterSchema(db, 1);
            AtomicBoolean stop = new AtomicBoolean(false);
            Thread[] fast = new Thread[6];
            for (int i = 0; i < 6; i++) {
                fast[i] = new Thread(() -> {
                    try (Connection c = Db.connect(db)) {
                        c.createStatement().execute("SET LOCK_TIMEOUT 60000");
                        while (!stop.get()) Store.optimisticIncrement(c, 0, 0);
                    } catch (SQLException e) { throw new RuntimeException(e); }
                });
                fast[i].start();
            }
            int saved = 0, lost = 0, escalations = 0;
            long budgetMs = 8000, t0 = System.currentTimeMillis();
            try (Connection c = Db.connect(db)) {
                c.createStatement().execute("SET LOCK_TIMEOUT 60000");
                while (saved < 5 && System.currentTimeMillis() - t0 < budgetMs) {
                    int lostThisSave = 0;
                    boolean done = false;
                    while (!done && System.currentTimeMillis() - t0 < budgetMs) {
                        if (escalate && lostThisSave >= 3) {
                            Store.pessimisticIncrement(c, 0, 30); // take the queue spot
                            escalations++;
                            done = true;
                        } else if (attempt(c, 30)) {
                            done = true;
                        } else { lost++; lostThisSave++; }
                    }
                    if (done) saved++;
                }
            }
            long ms = System.currentTimeMillis() - t0;
            stop.set(true);
            for (Thread t : fast) t.join();
            System.out.printf("%-24s: %d/5 saves in %5dms, %4d lost races%s%n",
                escalate ? "escalate after 3 losses" : "pure optimistic",
                saved, ms, lost,
                escalate ? ", " + escalations + " escalations" : (saved < 5 ? "  <- STARVED (no error, no progress)" : ""));
        }
    }
}
