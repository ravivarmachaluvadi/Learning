import java.sql.*;
import java.util.function.*;

/**
 * The piece of code that every use of a high isolation level requires, and that
 * most applications are missing.
 *
 * At SNAPSHOT and above the database's way of preventing an anomaly is to REFUSE
 * one of the transactions. That refusal is not a bug and not an outage — it is
 * the guarantee being delivered, and it is addressed to you. If you do not catch
 * it and run the transaction again, you have bought the isolation level and
 * thrown away what it bought you.
 */
final class Retry {

    /** The SQLSTATEs that mean "run it again": serialization failure and deadlock. */
    static boolean isRetryable(SQLException e) {
        String s = e.getSQLState();
        if (s == null) return false;
        return s.startsWith("40001") || s.startsWith("40P01") || s.equals("40000");
    }

    static final class Stats {
        int attempts, retries, giveUps;
        @Override public String toString() { return attempts + " attempts, " + retries + " retries"; }
    }

    /**
     * Runs `work` inside one transaction at `level`, retrying from the beginning
     * if the database refuses it. Retrying the WHOLE transaction matters: the
     * refusal means the data your decision was based on is no longer true, so
     * re-running just the failed statement would decide the same wrong thing again.
     */
    static <T> T inTransaction(int level, int maxAttempts, Stats stats, SqlFunction<T> work) throws SQLException {
        SQLException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try (Connection c = Db.open()) {
                c.setTransactionIsolation(level);
                Db.relaxLockTimeout(c);
                if (stats != null) stats.attempts++;
                try {
                    T out = work.apply(c);
                    c.commit();
                    return out;
                } catch (SQLException e) {
                    try { c.rollback(); } catch (SQLException ignored) {}
                    if (!isRetryable(e)) throw e;
                    last = e;
                    if (stats != null) stats.retries++;
                }
            }
            try { Thread.sleep(1 + (long) (Math.random() * attempt * 2)); }   // back off a little, and jitter
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        if (stats != null) stats.giveUps++;
        throw last != null ? last : new SQLException("gave up after " + maxAttempts + " attempts");
    }

    interface SqlFunction<T> { T apply(Connection c) throws SQLException; }
}
