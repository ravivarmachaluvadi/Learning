import java.sql.*;

/** Connection plumbing, plus the level names, kept in one place. */
final class Db {

    /** H2 adds a sixth level of its own; JDBC only standardises the other four. */
    static final int SNAPSHOT = 6;

    static final int[] LEVELS = {
        Connection.TRANSACTION_READ_UNCOMMITTED,   // 1
        Connection.TRANSACTION_READ_COMMITTED,     // 2
        Connection.TRANSACTION_REPEATABLE_READ,    // 4
        SNAPSHOT,                                  // 6  (H2 only)
        Connection.TRANSACTION_SERIALIZABLE        // 8
    };

    static String name(int level) {
        return switch (level) {
            case Connection.TRANSACTION_READ_UNCOMMITTED -> "READ UNCOMMITTED";
            case Connection.TRANSACTION_READ_COMMITTED   -> "READ COMMITTED";
            case Connection.TRANSACTION_REPEATABLE_READ  -> "REPEATABLE READ";
            case SNAPSHOT                                -> "SNAPSHOT";
            case Connection.TRANSACTION_SERIALIZABLE     -> "SERIALIZABLE";
            default -> "LEVEL " + level;
        };
    }

    static String shortName(int level) {
        return switch (level) {
            case 1 -> "RU"; case 2 -> "RC"; case 4 -> "RR"; case 6 -> "SNAP"; case 8 -> "SER";
            default -> String.valueOf(level);
        };
    }

    /** The default target: an in-memory H2 that lives as long as the JVM. */
    static String url = System.getProperty("db.url", "jdbc:h2:mem:lab;DB_CLOSE_DELAY=-1");
    static String user = System.getProperty("db.user", "sa");
    static String pass = System.getProperty("db.pass", "");

    static Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(url, user, pass);
        c.setAutoCommit(false);
        return c;
    }

    /**
     * A transaction that is waiting for a row lock must LOOK blocked to the
     * harness, not be killed by the driver before the harness notices. So the
     * database's own lock timeout is pushed well past the harness's step
     * timeout; anything that still fails is the database making a decision,
     * not the clock running out.
     */
    static void relaxLockTimeout(Connection c) {
        try (Statement s = c.createStatement()) {
            s.execute("SET LOCK_TIMEOUT 15000");
        } catch (SQLException ignored) {
            // not an H2 setting on other engines; their own defaults are fine
        }
    }

    static String product() {
        try (Connection c = DriverManager.getConnection(url, user, pass)) {
            DatabaseMetaData m = c.getMetaData();
            return m.getDatabaseProductName() + " " + m.getDatabaseProductVersion();
        } catch (SQLException e) { return "unknown (" + e.getMessage() + ")"; }
    }

    static void exec(Connection c, String... sql) throws SQLException {
        try (Statement s = c.createStatement()) { for (String q : sql) s.execute(q); }
    }

    static String one(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            return r.next() ? String.valueOf(r.getObject(1)) : "(no rows)";
        }
    }
}
