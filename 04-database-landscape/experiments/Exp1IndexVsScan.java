import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Experiment 1 — the index's power and its boundary.
 *
 * Claim under test (README §10 trick 2, incident 1):
 *   A B-tree index turns a SELECTIVE lookup from O(n) scan into O(log n) walk —
 *   but does nothing for an aggregate that must visit every row anyway.
 *
 * Row store: H2, in-memory. Run (from the lab directory):
 *   java --enable-native-access=ALL-UNNAMED -cp ../lib/duckdb_jdbc.jar:../lib/h2.jar experiments/Exp1IndexVsScan.java
 */
public class Exp1IndexVsScan {

    static final int N = 1_000_000;
    // The Python original took the best of 5. A JVM under a GC load this size is a
    // noisier clock than CPython, so we take the best of more passes; the extra
    // runs cost a few seconds and keep the "no help" ratio from wandering.
    static final int REPEAT = 9;

    /** Somewhere for query results to go, so the JIT cannot delete the query. */
    static long sink;

    public static void main(String[] args) throws Exception {
        // H2 keeps TWO result caches, and either one silently invalidates this
        // experiment: QUERY_CACHE_SIZE remembers the result of recently seen SQL
        // text, and OPTIMIZE_REUSE_RESULTS hands back a prepared statement's last
        // result whenever the table has not changed since. With them on, runs
        // 2..5 of each query below come back memoised in ~0.1 ms and we would be
        // timing a cache instead of a storage layout. Both off.
        Connection db = DriverManager.getConnection(
                "jdbc:h2:mem:exp1;QUERY_CACHE_SIZE=0;OPTIMIZE_REUSE_RESULTS=0", "sa", "");

        System.out.printf("Building %,d orders (this takes a few seconds)...%n", N);
        try (Statement s = db.createStatement()) {
            s.execute("CREATE TABLE orders (id INT PRIMARY KEY, user_id INT, price DOUBLE)");
            // SYSTEM_RANGE is H2's row generator. Every column is a deterministic
            // formula of the row number, so each run builds byte-identical data.
            s.execute("INSERT INTO orders "
                    + "SELECT X, X % 100000, ((X * 2654435761) % 100000) / 100.0 "
                    + "FROM SYSTEM_RANGE(1, " + N + ")");
        }

        String qLookup = "SELECT COUNT(*), SUM(price) FROM orders WHERE user_id = 424242 % 100000";
        String qAggregate = "SELECT SUM(price) FROM orders";

        System.out.println("\n--- WITHOUT an index on user_id ---");
        double tScan = timed("lookup one user's orders (full table scan)", query(db, qLookup));
        double tAggBefore = timed("SUM(price) over all rows", query(db, qAggregate));

        System.out.println("\nCreating index on user_id...");
        try (Statement s = db.createStatement()) {
            s.execute("CREATE INDEX idx_user ON orders(user_id)");
        }

        System.out.println("\n--- WITH the index ---");
        // The selective query now walks ~3 B-tree levels to the matching rows instead of
        // visiting all 1M rows — this is the entire value proposition of an index.
        double tIdx = timed("lookup one user's orders (B-tree walk)", query(db, qLookup));
        // The aggregate still has to visit every row's price; the index is irrelevant to it.
        double tAggAfter = timed("SUM(price) over all rows (index can't help)", query(db, qAggregate));

        System.out.println("\n=== VERDICT ===");
        System.out.printf("Selective lookup speedup from the index : %,.0fx%n", tScan / tIdx);
        System.out.printf("Aggregate 'speedup' from the same index : %.2fx  (~1 = no help)%n",
                tAggBefore / tAggAfter);
        System.out.println(
                "\nLesson: indexes accelerate SELECTIVITY, not VOLUME. A query that must touch\n"
              + "every row (incident 1's dashboard) is not an indexing problem — it is a\n"
              + "storage-layout problem, which is why columnar stores (exp3) exist.");
    }

    /** One executable query, prepared once so we time the engine and not the SQL parser. */
    static Action query(Connection db, String sql) throws SQLException {
        PreparedStatement ps = db.prepareStatement(sql);
        return () -> {
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) sink += r.getLong(1);
            }
        };
    }

    /** Best (= minimum) of REPEAT runs, after a warm-up. */
    static double timed(String label, Action fn) throws SQLException {
        // WARM-UP: Java runs new code interpreted until the JIT compiles it. Without
        // these throwaway passes, the FIRST query timed in the program would be several
        // times slower than the rest and every ratio printed below would be a
        // measurement of the compiler rather than of the database.
        for (int i = 0; i < 2; i++) fn.run();
        double best = Double.MAX_VALUE;
        for (int i = 0; i < REPEAT; i++) best = Math.min(best, timeOnce(fn));
        System.out.printf("  %-58s %10.3f ms%n", label, best * 1000);
        return best;
    }

    static double timeOnce(Action fn) throws SQLException {
        long t0 = System.nanoTime();
        fn.run();
        return (System.nanoTime() - t0) / 1e9;
    }

    @FunctionalInterface
    interface Action {
        void run() throws SQLException;
    }
}
