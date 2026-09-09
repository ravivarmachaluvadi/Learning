import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Scenario checks, named after the claims in README.md.
 *
 *   java -cp ../lib/h2.jar src/Tests.java          -> all guarantees
 *   java -cp ../lib/h2.jar src/Tests.java broken   -> planted bugs, each must be caught
 */
public class Tests {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("  PASS  " + name); }
        else    { failed++; System.out.println("  FAIL  " + name + "  [" + detail + "]"); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("broken")) { brokenMode(); return; }

        naive_second_save_erases_first_writers_change();
        optimistic_save_detects_the_same_conflict();
        optimistic_retry_rereads_so_both_edits_survive();
        version_check_works_across_requests_and_minutes();
        optimistic_race_8x40_converges_to_320();
        pessimistic_race_8x40_converges_to_320();
        for_update_blocks_until_commit();
        nowait_fails_immediately_instead_of_queueing();
        skip_locked_returns_only_unclaimed_rows();
        thinking_while_holding_a_lock_stops_everyone();
        thinking_without_a_lock_stops_no_one();
        pessimistic_can_deadlock_optimistic_cannot();
        write_skew_survives_for_update_on_own_rows();
        shared_version_bump_stops_write_skew();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- scenarios

    /** Meera edits the description, Sam (who read before her save) edits the
     *  price. Both "succeed". Meera's change is gone — Sam's stale copy of
     *  the description travelled with his save. */
    static void naive_second_save_erases_first_writers_change() throws Exception {
        String db = Db.freshDb("naive");
        Db.productSchema(db);
        try (Connection meera = Db.connect(db); Connection sam = Db.connect(db)) {
            Store.Product mSees = Store.read(meera, 42); meera.commit();
            Store.Product sSees = Store.read(sam, 42);   sam.commit();
            Store.naiveSave(meera, new Store.Product(42, "Steel bottle, 500ml, BPA-free", mSees.price(), 0));
            meera.commit();
            Store.naiveSave(sam, new Store.Product(42, sSees.description(), 1200, 0));
            sam.commit();
            Store.Product fin = Store.read(sam, 42); sam.commit();
            check("naive_second_save_erases_first_writers_change",
                  fin.description().equals("Steel water bottle, 500ml") && fin.price() == 1200,
                  "final=" + fin);
        }
    }

    /** Same interleaving, but saves carry the version they read. Sam's save
     *  matches 0 rows and surfaces as an exception instead of silent loss. */
    static void optimistic_save_detects_the_same_conflict() throws Exception {
        String db = Db.freshDb("detect");
        Db.productSchema(db);
        try (Connection meera = Db.connect(db); Connection sam = Db.connect(db)) {
            Store.Product mSees = Store.read(meera, 42); meera.commit();
            Store.Product sSees = Store.read(sam, 42);   sam.commit();
            Store.optimisticSave(meera, new Store.Product(42, "Steel bottle, 500ml, BPA-free",
                                                          mSees.price(), mSees.version()));
            meera.commit();
            boolean caught = false;
            try {
                Store.optimisticSave(sam, new Store.Product(42, sSees.description(), 1200,
                                                            sSees.version()));
                sam.commit();
            } catch (Store.StaleVersionException e) { caught = true; sam.rollback(); }
            Store.Product fin = Store.read(sam, 42); sam.commit();
            check("optimistic_save_detects_the_same_conflict",
                  caught && fin.description().contains("BPA-free"),
                  "caught=" + caught + " final=" + fin);
        }
    }

    /** The full loop: Sam's conflicted save re-reads and re-applies only his
     *  intended change (the price) to the fresh row. Both edits survive. */
    static void optimistic_retry_rereads_so_both_edits_survive() throws Exception {
        String db = Db.freshDb("merge");
        Db.productSchema(db);
        try (Connection meera = Db.connect(db); Connection sam = Db.connect(db)) {
            Store.Product sSees = Store.read(sam, 42); sam.commit(); // Sam's form snapshot
            Store.optimisticUpdate(meera, 42,
                p -> new Store.Product(42, "Steel bottle, 500ml, BPA-free", p.price(), p.version()));
            boolean conflicted = false;
            try { // Sam saves from the stale form: MUST be rejected first
                Store.optimisticSave(sam, new Store.Product(42, sSees.description(), 1200, sSees.version()));
                sam.commit();
            } catch (Store.StaleVersionException e) {
                sam.rollback();
                conflicted = true; // then: re-read, re-apply only his own edit
                Store.optimisticUpdate(sam, 42,
                    p -> new Store.Product(42, p.description(), 1200, p.version()));
            }
            Store.Product fin = Store.read(sam, 42); sam.commit();
            check("optimistic_retry_rereads_so_both_edits_survive",
                  conflicted && fin.description().contains("BPA-free") && fin.price() == 1200,
                  "conflicted=" + conflicted + " final=" + fin);
        }
    }

    /** The version is data, so it survives connection close, "HTTP response",
     *  think time, and a brand-new connection — no transaction spans the gap.
     *  This is the part no isolation level or FOR UPDATE can do at all. */
    static void version_check_works_across_requests_and_minutes() throws Exception {
        String db = Db.freshDb("offline");
        Db.productSchema(db);
        long formVersion;
        try (Connection req1 = Db.connect(db)) {          // GET /product/42/edit
            formVersion = Store.read(req1, 42).version();
            req1.commit();
        }                                                  // connection gone; form holds v0
        try (Connection other = Db.connect(db)) {          // someone else saves meanwhile
            Store.optimisticUpdate(other, 42,
                p -> new Store.Product(42, p.description(), 1800, p.version()));
        }
        boolean caught = false;
        try (Connection req2 = Db.connect(db)) {           // POST /product/42 with v0
            try {
                Store.optimisticSave(req2, new Store.Product(42, "vandalised", 999, formVersion));
                req2.commit();
            } catch (Store.StaleVersionException e) { caught = true; req2.rollback(); }
        }
        check("version_check_works_across_requests_and_minutes", caught, "stale save was accepted");
    }

    /** 8 clients x 40 read-modify-write increments on one row. The mirror of
     *  the isolation-levels lab's 320-op experiment: there, read committed
     *  lost ~80% of these. The version check gets all 320 at read committed. */
    static void optimistic_race_8x40_converges_to_320() throws Exception {
        String db = Db.freshDb("optrace");
        Db.counterSchema(db, 1);
        AtomicInteger retries = new AtomicInteger();
        Db.race(8, i -> {
            try (Connection c = Db.connect(db)) {
                for (int k = 0; k < 40; k++)
                    retries.addAndGet(Store.optimisticIncrement(c, 0, 0));
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
        int total;
        try (Connection c = Db.connect(db)) { total = Db.queryInt(c, "SELECT n FROM counter WHERE id=0"); }
        check("optimistic_race_8x40_converges_to_320", total == 320,
              "total=" + total + " retries=" + retries);
    }

    static void pessimistic_race_8x40_converges_to_320() throws Exception {
        String db = Db.freshDb("pesrace");
        Db.counterSchema(db, 1);
        Db.race(8, i -> {
            try (Connection c = Db.connect(db)) {
                c.createStatement().execute("SET LOCK_TIMEOUT 20000");
                for (int k = 0; k < 40; k++) Store.pessimisticIncrement(c, 0, 0);
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
        int total;
        try (Connection c = Db.connect(db)) { total = Db.queryInt(c, "SELECT n FROM counter WHERE id=0"); }
        check("pessimistic_race_8x40_converges_to_320", total == 320, "total=" + total);
    }

    /** FOR UPDATE is a queue: the second locker proceeds the moment the first
     *  commits, and not before. */
    static void for_update_blocks_until_commit() throws Exception {
        String db = Db.freshDb("block");
        Db.productSchema(db);
        long holdMs = 400;
        CountDownLatch locked = new CountDownLatch(1);
        AtomicLong waited = new AtomicLong(-1);
        Thread holder = new Thread(() -> {
            try (Connection a = Db.connect(db)) {
                Store.readForUpdate(a, 42);
                locked.countDown();
                Db.sleep(holdMs);
                a.commit();
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
        holder.start();
        locked.await();
        long t0 = System.currentTimeMillis();
        try (Connection b = Db.connect(db)) {
            b.createStatement().execute("SET LOCK_TIMEOUT 10000");
            Store.readForUpdate(b, 42);
            waited.set(System.currentTimeMillis() - t0);
            b.commit();
        }
        holder.join();
        check("for_update_blocks_until_commit",
              waited.get() >= holdMs - 100 && waited.get() < holdMs + 2000,
              "waited=" + waited.get() + "ms while holder held " + holdMs + "ms");
    }

    /** NOWAIT converts "queue behind them" into "tell me right now". */
    static void nowait_fails_immediately_instead_of_queueing() throws Exception {
        String db = Db.freshDb("nowait");
        Db.productSchema(db);
        try (Connection a = Db.connect(db); Connection b = Db.connect(db)) {
            Store.readForUpdate(a, 42);
            long t0 = System.currentTimeMillis();
            boolean immediate = false;
            try {
                b.createStatement().executeQuery("SELECT * FROM product WHERE id=42 FOR UPDATE NOWAIT");
            } catch (SQLException e) {
                immediate = (System.currentTimeMillis() - t0) < 100 && e.getErrorCode() == 50200;
            }
            a.rollback(); b.rollback();
            check("nowait_fails_immediately_instead_of_queueing", immediate, "no fast failure");
        }
    }

    /** SKIP LOCKED: locked rows become invisible instead of blocking —
     *  the primitive that turns a table into a job queue. */
    static void skip_locked_returns_only_unclaimed_rows() throws Exception {
        String db = Db.freshDb("skip");
        Db.jobSchema(db, 3);
        try (Connection a = Db.connect(db); Connection b = Db.connect(db)) {
            a.createStatement().executeQuery("SELECT * FROM job WHERE id=0 FOR UPDATE");
            List<Integer> seen = new ArrayList<>();
            try (ResultSet r = b.createStatement().executeQuery(
                    "SELECT id FROM job FOR UPDATE SKIP LOCKED")) {
                while (r.next()) seen.add(r.getInt(1));
            }
            a.rollback(); b.rollback();
            check("skip_locked_returns_only_unclaimed_rows",
                  seen.equals(List.of(1, 2)), "saw " + seen);
        }
    }

    /** The lunch-break problem: one pessimistic client pauses for 800ms
     *  between lock and commit. Five other writers get NOTHING done. */
    static void thinking_while_holding_a_lock_stops_everyone() throws Exception {
        int done = lunchBreak(true);
        check("thinking_while_holding_a_lock_stops_everyone", done == 0,
              "others completed " + done + " saves during the pause");
    }

    /** Same pause under the optimistic strategy: the thinker holds no lock,
     *  so the same five writers all get through. The pause costs only the
     *  thinker (a conflict on wake-up), never the neighbours. */
    static void thinking_without_a_lock_stops_no_one() throws Exception {
        int done = lunchBreak(false);
        check("thinking_without_a_lock_stops_no_one", done >= 5,
              "others completed only " + done + " saves");
    }

    /** One slow client pauses `pauseMs` mid-flight; 5 fast clients each try
     *  one price update during the pause. Returns how many fast saves landed
     *  before the slow client finished. */
    static int lunchBreak(boolean pessimistic) throws Exception {
        String db = Db.freshDb("lunch");
        Db.productSchema(db);
        long pauseMs = 800;
        CountDownLatch slowStarted = new CountDownLatch(1);
        AtomicInteger fastDone = new AtomicInteger();
        Thread slow = new Thread(() -> {
            try (Connection c = Db.connect(db)) {
                if (pessimistic) {
                    Store.Product p = Store.readForUpdate(c, 42);
                    slowStarted.countDown();
                    Db.sleep(pauseMs);                       // phone rings, lock held
                    Store.naiveSave(c, new Store.Product(42, p.description(), 1111, 0));
                    c.commit();
                } else {
                    Store.Product p = Store.read(c, 42); c.commit();
                    slowStarted.countDown();
                    Db.sleep(pauseMs);                       // phone rings, NO lock held
                    try {
                        Store.optimisticSave(c, new Store.Product(42, p.description(), 1111, p.version()));
                        c.commit();
                    } catch (Store.StaleVersionException e) { c.rollback(); } // pays alone
                }
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
        slow.start();
        slowStarted.await();
        long deadline = System.currentTimeMillis() + pauseMs - 150; // clearly inside the pause
        Db.race(5, i -> {
            try (Connection c = Db.connect(db)) {
                c.createStatement().execute("SET LOCK_TIMEOUT " + pauseMs * 3);
                Store.optimisticUpdate(c, 42, p -> new Store.Product(42, p.description(),
                                                                     2000 + i, p.version()));
                if (System.currentTimeMillis() < deadline) fastDone.incrementAndGet();
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
        slow.join();
        return fastDone.get();
    }

    /** Opposite-order FOR UPDATE on two accounts: H2 detects the cycle and
     *  kills one victim (SQLState 40001). The optimistic version of the same
     *  transfer cannot deadlock — nobody ever WAITS holding something. */
    static void pessimistic_can_deadlock_optimistic_cannot() throws Exception {
        String db = Db.freshDb("dead");
        Db.counterSchema(db, 2);
        AtomicInteger deadlocks = new AtomicInteger();
        CyclicBarrier both = new CyclicBarrier(2);
        Db.race(2, i -> {
            int first = i, second = 1 - i;                  // opposite orders
            try (Connection c = Db.connect(db)) {
                c.createStatement().execute("SET LOCK_TIMEOUT 5000");
                c.createStatement().executeQuery("SELECT n FROM counter WHERE id=" + first + " FOR UPDATE");
                both.await();                               // both hold one row
                try {
                    c.createStatement().executeQuery("SELECT n FROM counter WHERE id=" + second + " FOR UPDATE");
                } catch (SQLException e) {
                    if ("40001".equals(e.getSQLState())) deadlocks.incrementAndGet();
                }
                c.rollback();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        // Optimistic transfers in opposite orders: 2 threads x 20 rounds, no waiting-while-holding.
        AtomicInteger optDeadlocks = new AtomicInteger();
        Db.race(2, i -> {
            try (Connection c = Db.connect(db)) {
                c.createStatement().execute("SET LOCK_TIMEOUT 5000");
                for (int k = 0; k < 20; k++) {
                    try {
                        Store.optimisticIncrement(c, i, 0);       // "debit"
                        Store.optimisticIncrement(c, 1 - i, 0);   // "credit"
                    } catch (SQLException e) {
                        if ("40001".equals(e.getSQLState())) optDeadlocks.incrementAndGet();
                        else throw new RuntimeException(e);
                    }
                }
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
        check("pessimistic_can_deadlock_optimistic_cannot",
              deadlocks.get() >= 1 && optDeadlocks.get() == 0,
              "pessimistic deadlocks=" + deadlocks + " optimistic deadlocks=" + optDeadlocks);
    }

    // -- The on-call roster from the isolation-levels lab, one level up. -----
    // Rule: at least 1 of 2 doctors on call. Each doctor locks and updates
    // ONLY THEIR OWN row, so no lock collision ever happens — FOR UPDATE on
    // your own row cannot see a rule about the PAIR of rows.

    static void rosterSchema(String db) {
        try (Connection c = Db.connect(db); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE oncall (doctor VARCHAR PRIMARY KEY, on_call BOOLEAN NOT NULL)");
            s.execute("INSERT INTO oncall VALUES ('alice', TRUE), ('bob', TRUE)");
            s.execute("CREATE TABLE roster (id INT PRIMARY KEY, version BIGINT NOT NULL)");
            s.execute("INSERT INTO roster VALUES (1, 0)");
            c.commit();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    static void write_skew_survives_for_update_on_own_rows() throws Exception {
        String db = Db.freshDb("skew");
        rosterSchema(db);
        CyclicBarrier bothChecked = new CyclicBarrier(2);
        String[] docs = {"alice", "bob"};
        Db.race(2, i -> {
            try (Connection c = Db.connect(db)) {
                int onCall = Db.queryInt(c, "SELECT COUNT(*) FROM oncall WHERE on_call");
                bothChecked.await();                        // both saw 2 >= 2, both proceed
                if (onCall >= 2) {
                    // locks only the doctor's own row — never conflicts
                    c.createStatement().executeQuery(
                        "SELECT * FROM oncall WHERE doctor='" + docs[i] + "' FOR UPDATE");
                    c.createStatement().executeUpdate(
                        "UPDATE oncall SET on_call=FALSE WHERE doctor='" + docs[i] + "'");
                }
                c.commit();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        int left;
        try (Connection c = Db.connect(db)) { left = Db.queryInt(c, "SELECT COUNT(*) FROM oncall WHERE on_call"); }
        check("write_skew_survives_for_update_on_own_rows", left == 0,
              "expected the anomaly (0 on call), got " + left);
    }

    /** The fix: every transaction that RELIES on the pair also bumps one
     *  shared version row. Now the two "non-conflicting" transactions collide
     *  on purpose, and first-committer-wins picks a survivor. This is what
     *  JPA's OPTIMISTIC_FORCE_INCREMENT on an aggregate root does. */
    static void shared_version_bump_stops_write_skew() throws Exception {
        String db = Db.freshDb("skewfix");
        rosterSchema(db);
        CyclicBarrier bothChecked = new CyclicBarrier(2);
        String[] docs = {"alice", "bob"};
        AtomicInteger rejected = new AtomicInteger();
        Db.race(2, i -> {
            try (Connection c = Db.connect(db)) {
                c.createStatement().execute("SET LOCK_TIMEOUT 5000");
                long v = Db.queryInt(c, "SELECT version FROM roster WHERE id=1");
                int onCall = Db.queryInt(c, "SELECT COUNT(*) FROM oncall WHERE on_call");
                bothChecked.await();
                if (onCall >= 2) {
                    c.createStatement().executeUpdate(
                        "UPDATE oncall SET on_call=FALSE WHERE doctor='" + docs[i] + "'");
                    // the collision point: the rule's guardian row
                    int n = c.createStatement().executeUpdate(
                        "UPDATE roster SET version=version+1 WHERE id=1 AND version=" + v);
                    if (n == 0) { c.rollback(); rejected.incrementAndGet(); return; }
                }
                c.commit();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        int left;
        try (Connection c = Db.connect(db)) { left = Db.queryInt(c, "SELECT COUNT(*) FROM oncall WHERE on_call"); }
        check("shared_version_bump_stops_write_skew", left == 1 && rejected.get() == 1,
              "on call=" + left + " rejected=" + rejected);
    }

    // ---------------------------------------------------------------- broken mode

    /** Each planted bug must make a specific guarantee fail — that failure is
     *  the point: it shows which line of the correct code was load-bearing. */
    static void brokenMode() throws Exception {
        System.out.println("Planted bug: no-count-check (update count ignored)");
        Store.BUG = "no-count-check";
        String db = Db.freshDb("bug1");
        Db.productSchema(db);
        try (Connection meera = Db.connect(db); Connection sam = Db.connect(db)) {
            Store.Product mSees = Store.read(meera, 42); meera.commit();
            Store.Product sSees = Store.read(sam, 42);   sam.commit();
            Store.optimisticSave(meera, new Store.Product(42, "Steel bottle, BPA-free", mSees.price(), mSees.version()));
            meera.commit();
            Store.optimisticSave(sam, new Store.Product(42, sSees.description(), 1200, sSees.version()));
            sam.commit();  // no exception anywhere...
            Store.Product fin = Store.read(sam, 42); sam.commit();
            // ...and no effect either: 0 rows matched, Sam's edit VANISHED silently.
            check("bug_caught: no-count-check turns conflicts into silent no-ops",
                  fin.price() == 1500 && fin.description().contains("BPA-free"),
                  "final=" + fin);
        }

        System.out.println("Planted bug: no-version-bump (version checked but never advanced)");
        Store.BUG = "no-version-bump";
        String db2 = Db.freshDb("bug2");
        Db.productSchema(db2);
        try (Connection meera = Db.connect(db2); Connection sam = Db.connect(db2)) {
            Store.Product mSees = Store.read(meera, 42); meera.commit();
            Store.Product sSees = Store.read(sam, 42);   sam.commit();
            Store.optimisticSave(meera, new Store.Product(42, "Steel bottle, BPA-free", mSees.price(), mSees.version()));
            meera.commit();
            boolean staleAccepted = true;
            try {
                Store.optimisticSave(sam, new Store.Product(42, sSees.description(), 1200, sSees.version()));
                sam.commit();  // version 0 still matches -> stale save lands, Meera's edit dies
            } catch (Store.StaleVersionException e) { staleAccepted = false; sam.rollback(); }
            Store.Product fin = Store.read(sam, 42); sam.commit();
            check("bug_caught: no-version-bump lets the second stale save win",
                  staleAccepted && !fin.description().contains("BPA-free"),
                  "staleAccepted=" + staleAccepted + " final=" + fin);
        }

        System.out.println("Planted bug: stale-reapply (retry refreshes version, keeps old value)");
        Store.BUG = "stale-reapply";
        String db3 = Db.freshDb("bug3");
        Db.counterSchema(db3, 1);
        Db.race(8, i -> {
            try (Connection c = Db.connect(db3)) {
                for (int k = 0; k < 40; k++)
                    Store.optimisticUpdateCounter(c, 0);
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
        Store.BUG = "none";
        int total;
        try (Connection c = Db.connect(db3)) { total = Db.queryInt(c, "SELECT n FROM counter WHERE id=0"); }
        check("bug_caught: stale-reapply loses increments despite retrying (" + total + "/320)",
              total < 320, "total=" + total + " — bug did not reproduce");

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
