import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * EXERCISE (rung 5): implement the two optimistic primitives yourself.
 *
 *   java -cp ../lib/h2.jar src/YourStrategy.java
 *
 * The two methods below currently contain the NAIVE versions. Replace their
 * bodies until all 6 checks pass. Rules: no FOR UPDATE anywhere — both
 * primitives must be optimistic. SOLUTIONS.md has the answers.
 */
public class YourStrategy {

    /**
     * TODO #1 — optimistic save.
     * Must: write description+price ONLY if the row's version still equals
     * p.version(); advance the version on success; throw
     * Store.StaleVersionException when someone got there first.
     * (Currently: the naive save — writes unconditionally.)
     */
    static void yourOptimisticSave(Connection c, Store.Product p) throws SQLException {
        try (PreparedStatement u = c.prepareStatement(
                "UPDATE product SET description=?, price=? WHERE id=?")) {
            u.setString(1, p.description());
            u.setInt(2, p.price());
            u.setInt(3, p.id());
            u.executeUpdate();
        }
    }

    /**
     * TODO #2 — optimistic job claim.
     * Must: return the id of ONE previously-PENDING job now claimed as
     * RUNNING by this worker, or -1 if none left. Two workers must never
     * claim the same job. No FOR UPDATE: use the status column itself as
     * the version ("PENDING" is the expected value).
     * (Currently: read-then-write with no condition — two workers can
     * both "claim" the same job.)
     */
    static int yourClaimJob(Connection c, String worker) throws SQLException {
        try (ResultSet r = c.createStatement().executeQuery(
                "SELECT id FROM job WHERE status='PENDING' ORDER BY id LIMIT 1")) {
            if (!r.next()) { c.commit(); return -1; }
            int id = r.getInt(1);
            c.createStatement().executeUpdate(
                "UPDATE job SET status='RUNNING', worker='" + worker + "' WHERE id=" + id);
            c.commit();
            return id;
        }
    }

    // ------------------------------------------------------------------ checks

    static int passed = 0, failed = 0;
    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("  PASS  " + name); }
        else    { failed++; System.out.println("  FAIL  " + name + "  [" + detail + "]"); }
    }

    public static void main(String[] args) throws Exception {
        // 1: a save with the current version must land
        String db = Db.freshDb("ys1");
        Db.productSchema(db);
        try (Connection c = Db.connect(db)) {
            yourOptimisticSave(c, new Store.Product(42, "edited", 1600, 0));
            c.commit();
            Store.Product p = Store.read(c, 42); c.commit();
            check("1_current_version_save_lands", p.description().equals("edited") && p.price() == 1600,
                  "row=" + p);
            // 2: ...and must advance the version
            check("2_version_advances_on_save", p.version() == 1, "version=" + p.version());
            // 3: a stale save must throw StaleVersionException
            boolean thrown = false;
            try { yourOptimisticSave(c, new Store.Product(42, "stale overwrite", 1, 0)); c.commit(); }
            catch (Store.StaleVersionException e) { thrown = true; c.rollback(); }
            Store.Product after = Store.read(c, 42); c.commit();
            check("3_stale_save_rejected", thrown && after.description().equals("edited"),
                  "thrown=" + thrown + " row=" + after);
        }

        // 4: 8 clients x 20 read-modify-write price bumps -> exactly +160
        String db2 = Db.freshDb("ys2");
        Db.productSchema(db2);
        Db.race(8, i -> {
            try (Connection c = Db.connect(db2)) {
                c.createStatement().execute("SET LOCK_TIMEOUT 20000");
                for (int k = 0; k < 20; k++) {
                    while (true) {
                        try {
                            Store.Product p = Store.read(c, 42);
                            yourOptimisticSave(c, new Store.Product(42, p.description(),
                                                                    p.price() + 1, p.version()));
                            c.commit();
                            break;
                        } catch (Store.StaleVersionException e) { c.rollback(); }
                    }
                }
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
        try (Connection c = Db.connect(db2)) {
            int price = Db.queryInt(c, "SELECT price FROM product WHERE id=42");
            check("4_concurrent_race_loses_nothing", price == 1500 + 160, "price=" + price + " expected 1660");
        }

        // 5: 4 workers claiming 40 jobs -> every job claimed exactly once
        String db3 = Db.freshDb("ys3");
        Db.jobSchema(db3, 40);
        Map<Integer, List<String>> claims = Collections.synchronizedMap(new TreeMap<>());
        Db.race(4, w -> {
            try (Connection c = Db.connect(db3)) {
                c.createStatement().execute("SET LOCK_TIMEOUT 20000");
                while (true) {
                    int id = yourClaimJob(c, "w" + w);
                    if (id < 0) break;
                    claims.computeIfAbsent(id, k -> new ArrayList<>()).add("w" + w);
                }
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
        int doubleClaimed = 0;
        for (List<String> owners : claims.values()) if (owners.size() > 1) doubleClaimed++;
        check("5_no_job_claimed_twice", claims.size() == 40 && doubleClaimed == 0,
              claims.size() + " claimed, " + doubleClaimed + " double-claimed");

        // 6: claim on an empty queue returns -1
        String db4 = Db.freshDb("ys4");
        Db.jobSchema(db4, 0);
        try (Connection c = Db.connect(db4)) {
            check("6_empty_queue_returns_minus_one", yourClaimJob(c, "w0") == -1, "did not return -1");
        }

        System.out.println();
        System.out.println(passed + "/6 passed" + (failed == 0 ? " — done!" : " — keep going"));
    }
}
