import java.sql.*;
import java.util.function.*;

/**
 * The three save strategies, written the way they would appear inside a
 * Spring @Repository — except in plain JDBC so every line is visible.
 *
 * Planted bugs (for Tests.java broken mode and the debug exercise) are
 * switched on via  -Dbug=no-count-check | no-version-bump | stale-reapply
 * Each bug is a real mistake seen in production code reviews.
 */
public class Store {

    public static String BUG = System.getProperty("bug", "none"); // mutable so Tests can flip bugs on

    /** What a read of the product row returns: data plus the version we saw.
     *  Carrying the version out of the transaction (into a form, a DTO, an
     *  HTTP response) is what lets optimistic locking span requests. */
    public record Product(int id, String description, int price, long version) {}

    public static Product read(Connection c, int id) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "SELECT id, description, price, version FROM product WHERE id=?")) {
            p.setInt(1, id);
            try (ResultSet r = p.executeQuery()) {
                r.next();
                return new Product(r.getInt(1), r.getString(2), r.getInt(3), r.getLong(4));
            }
        }
    }

    public static Product readForUpdate(Connection c, int id) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "SELECT id, description, price, version FROM product WHERE id=? FOR UPDATE")) {
            p.setInt(1, id);
            try (ResultSet r = p.executeQuery()) {
                r.next();
                return new Product(r.getInt(1), r.getString(2), r.getInt(3), r.getLong(4));
            }
        }
    }

    /** NAIVE: write every column from what the caller has in hand, no
     *  condition beyond the primary key. This is what a plain form POST or a
     *  full-entity ORM save does. Whoever saves last wins the whole row. */
    public static void naiveSave(Connection c, Product p) throws SQLException {
        try (PreparedStatement u = c.prepareStatement(
                "UPDATE product SET description=?, price=? WHERE id=?")) {
            u.setString(1, p.description());
            u.setInt(2, p.price());
            u.setInt(3, p.id());
            u.executeUpdate();
        }
    }

    /** Thrown when an optimistic save finds someone got there first.
     *  Spring's name for this is OptimisticLockingFailureException. */
    public static class StaleVersionException extends RuntimeException {
        StaleVersionException(int id, long expected) {
            super("product " + id + ": version " + expected + " is no longer current");
        }
    }

    /**
     * OPTIMISTIC: one UPDATE that both checks and claims.
     *
     *   UPDATE ... SET version = version+1 WHERE id=? AND version=?
     *
     * The WHERE clause makes the write conditional on the row still being the
     * one we read; the SET version+1 makes our own write change the condition
     * for everyone else. The database's row lock makes the check-and-write a
     * single atomic step — two concurrent saves cannot both match the same
     * version. This is exactly what JPA's @Version generates.
     *
     * The update COUNT is the verdict: 1 = our view was current, 0 = someone
     * committed in between. A zero is not an error from JDBC's point of view
     * — code that doesn't look at the count reintroduces silent lost updates
     * (bug: no-count-check).
     */
    public static void optimisticSave(Connection c, Product p) throws SQLException {
        String sql = BUG.equals("no-version-bump")
            // BUG: forgets to advance the version, so the version we consumed
            // stays current and a second stale writer will also match it.
            ? "UPDATE product SET description=?, price=? WHERE id=? AND version=?"
            : "UPDATE product SET description=?, price=?, version=version+1 " +
              "WHERE id=? AND version=?";
        try (PreparedStatement u = c.prepareStatement(sql)) {
            u.setString(1, p.description());
            u.setInt(2, p.price());
            u.setInt(3, p.id());
            u.setLong(4, p.version());
            int count = u.executeUpdate();
            if (BUG.equals("no-count-check")) return; // BUG: verdict ignored
            if (count == 0) throw new StaleVersionException(p.id(), p.version());
        }
    }

    /**
     * The retry loop that makes optimistic locking a complete strategy:
     * on conflict, RE-READ and RE-DECIDE against the fresh row, then try
     * again. `edit` is the business logic — it is re-run every attempt,
     * which is both the price (wasted work under contention) and the point
     * (the decision is always made against current truth).
     * Returns the number of retries (0 = clean first attempt).
     */
    public static int optimisticUpdate(Connection c, int id,
                                       UnaryOperator<Product> edit) throws SQLException {
        Product decided = null; // used only by the stale-reapply bug
        for (int retries = 0; ; retries++) {
            Product current = read(c, id);
            if (BUG.equals("stale-reapply") && decided != null) {
                // BUG: re-read only to refresh the version so the WHERE clause
                // passes, but keep the value computed from the ORIGINAL read.
                // The retry now overwrites whatever the other writer did —
                // "re-send" instead of "re-decide".
                decided = new Product(id, decided.description(), decided.price(),
                                      current.version());
            } else {
                decided = edit.apply(current);
            }
            try {
                optimisticSave(c, decided);
                c.commit();
                return retries;
            } catch (StaleVersionException e) {
                c.rollback();
                if (retries > 10_000) throw new RuntimeException("giving up", e);
            }
        }
    }

    /**
     * PESSIMISTIC: take the row lock BEFORE reading, so no conflicting write
     * can even begin until we commit. Between readForUpdate and commit we are
     * alone on this row — no version check needed, nothing to retry. The
     * price is paid by everyone else: they wait, for as long as we hold on.
     * JPA spelling: em.find(Product.class, id, LockModeType.PESSIMISTIC_WRITE).
     */
    public static void pessimisticUpdate(Connection c, int id,
                                         UnaryOperator<Product> edit) throws SQLException {
        Product current = readForUpdate(c, id);   // <-- blocks here if someone holds the row
        Product decided = edit.apply(current);
        try (PreparedStatement u = c.prepareStatement(
                "UPDATE product SET description=?, price=? WHERE id=?")) {
            u.setString(1, decided.description());
            u.setInt(2, decided.price());
            u.setInt(3, decided.id());
            u.executeUpdate();
        }
        c.commit(); // releases the lock — hold time = read-to-commit
    }

    // ---- same two strategies on the counter table (for measured races) -----

    /** Optimistic increment on counter row `id`, doing `workMs` of real CPU
     *  work between read and write (the business logic). Returns retries. */
    public static int optimisticIncrement(Connection c, int id, long workMs) throws SQLException {
        for (int retries = 0; ; retries++) {
            int n; long v;
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT n, version FROM counter WHERE id=?")) {
                p.setInt(1, id);
                try (ResultSet r = p.executeQuery()) { r.next(); n = r.getInt(1); v = r.getLong(2); }
            }
            Db.compute(workMs); // work done on a value that may already be stale
            try (PreparedStatement u = c.prepareStatement(
                    "UPDATE counter SET n=?, version=version+1 WHERE id=? AND version=?")) {
                u.setInt(1, n + 1);
                u.setInt(2, id);
                u.setLong(3, v);
                if (u.executeUpdate() == 1) { c.commit(); return retries; }
            }
            c.rollback(); // conflict: someone committed since our SELECT
        }
    }

    /** Counter increment via the full retry loop (read, decide, conditional
     *  write). Same shape as optimisticUpdate; hosts the stale-reapply bug
     *  for the counter races. Returns retries. */
    public static int optimisticUpdateCounter(Connection c, int id) throws SQLException {
        Integer decidedN = null;
        for (int retries = 0; ; retries++) {
            int n; long v;
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT n, version FROM counter WHERE id=?")) {
                p.setInt(1, id);
                try (ResultSet r = p.executeQuery()) { r.next(); n = r.getInt(1); v = r.getLong(2); }
            }
            if (!(BUG.equals("stale-reapply") && decidedN != null)) {
                decidedN = n + 1; // re-DECIDE from the fresh read (correct)
            }
            // else BUG: keep the value decided from the original read; only
            // the version is refreshed so the WHERE clause will now pass.
            try (PreparedStatement u = c.prepareStatement(
                    "UPDATE counter SET n=?, version=version+1 WHERE id=? AND version=?")) {
                u.setInt(1, decidedN);
                u.setInt(2, id);
                u.setLong(3, v);
                if (u.executeUpdate() == 1) { c.commit(); return retries; }
            }
            c.rollback();
        }
    }

    /** Pessimistic increment: lock, work, write, commit. Never retries;
     *  the same `workMs` now happens while the row is locked. */
    public static void pessimisticIncrement(Connection c, int id, long workMs) throws SQLException {
        int n;
        try (PreparedStatement p = c.prepareStatement(
                "SELECT n FROM counter WHERE id=? FOR UPDATE")) {
            p.setInt(1, id);
            try (ResultSet r = p.executeQuery()) { r.next(); n = r.getInt(1); }
        }
        Db.compute(workMs); // work done while everyone else waits
        try (PreparedStatement u = c.prepareStatement(
                "UPDATE counter SET n=? WHERE id=?")) {
            u.setInt(1, n + 1);
            u.setInt(2, id);
            u.executeUpdate();
        }
        c.commit();
    }
}
