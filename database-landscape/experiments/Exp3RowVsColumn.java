import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Experiment 3 — row store vs column store, same SQL, same data.
 *
 * Claims under test (README §10 trick 4, incidents 1 and its mirror F6):
 *   1. An analytic aggregate (GROUP BY over 5M rows, touching 2 of 8 columns) is
 *      ~10-100x faster on a columnar engine (DuckDB) than a row engine (H2),
 *      because the columnar engine reads only the queried columns' bytes.
 *   2. The bet reverses for OLTP: fetching ONE whole row by key favours the row
 *      store (one B-tree walk to one page) over the column store (reassemble the
 *      row from every column's segment).
 *
 * Needs both JDBC drivers from ../lib. Run (from the lab directory):
 *   java --enable-native-access=ALL-UNNAMED -cp ../lib/duckdb_jdbc.jar:../lib/h2.jar experiments/Exp3RowVsColumn.java
 *
 * Both engines are held entirely in RAM, so the only difference being measured is
 * the LAYOUT, never the storage medium. H2's 5M rows live on the JVM heap and peak
 * around 2.7 GB; the default max heap is a quarter of physical RAM, which is enough
 * on a 16 GB machine. On a smaller one, add -Xmx4g to the command above.
 */
public class Exp3RowVsColumn {

    static final int N = 5_000_000;

    // Identical data in both stores, built from the same deterministic formulas —
    // 8 columns so that row width matters, as in a realistic orders table.
    static final String COLUMNS =
            "id INTEGER, user_id INT, category INT, price DOUBLE, qty INT, city INT, ts INT, note VARCHAR";
    static final String AGG =
            "SELECT category, SUM(price) FROM orders GROUP BY category ORDER BY category";
    static final String POINT = "SELECT * FROM orders WHERE id = ?";

    static long sink;

    public static void main(String[] args) throws Exception {
        System.out.printf("Building %,d orders in BOTH engines from identical formulas...%n", N);

        long t0 = System.nanoTime();
        // H2's two result caches are switched off. Leave either on and the 2nd..5th
        // timed runs of the aggregate return a memoised answer in ~0.1 ms — the row
        // store would appear to WIN the scan, which would be a cache result being
        // mistaken for a storage-layout result.
        Connection lite = DriverManager.getConnection(
                "jdbc:h2:mem:exp3;QUERY_CACHE_SIZE=0;OPTIMIZE_REUSE_RESULTS=0", "sa", "");
        try (Statement s = lite.createStatement()) {
            s.execute("CREATE TABLE orders (" + COLUMNS.replace("id INTEGER", "id INTEGER PRIMARY KEY") + ")");
            // SYSTEM_RANGE is H2's row generator; DuckDB's equivalent is range().
            s.execute("INSERT INTO orders "
                    + "SELECT X, X % 100000, X % 12, ((X * 2654435761) % 100000) / 100.0, "
                    + "       X % 5 + 1, X % 200, X, 'order-' || X "
                    + "FROM SYSTEM_RANGE(1, " + N + ")");
        }
        System.out.printf("  H2 (row store)         built in %5.1f s%n", (System.nanoTime() - t0) / 1e9);

        t0 = System.nanoTime();
        Connection duck = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement s = duck.createStatement()) {
            s.execute("CREATE TABLE orders (" + COLUMNS + ")");
            s.execute("INSERT INTO orders "
                    + "SELECT range, range % 100000, range % 12, ((range * 2654435761) % 100000) / 100.0, "
                    + "       range % 5 + 1, range % 200, range, 'order-' || range "
                    + "FROM range(1, " + (N + 1L) + ")");
        }
        System.out.printf("  DuckDB (column store)  built in %5.1f s%n", (System.nanoTime() - t0) / 1e9);

        // --- The OLAP query: touches 2 of 8 columns, all 5M rows ------------------
        PreparedStatement liteAgg = lite.prepareStatement(AGG);
        PreparedStatement duckAgg = duck.prepareStatement(AGG);
        double tLiteAgg = bestOf(() -> drain(liteAgg), 5);
        double tDuckAgg = bestOf(() -> drain(duckAgg), 5);
        // (floating-point summation order differs between engines; totals agree to ~1e-6)
        requireSameAnswer(readAgg(liteAgg), readAgg(duckAgg));

        System.out.printf("%n--- OLAP: %s%n", AGG);
        System.out.printf("  H2 (reads whole 8-column rows)       %9.1f ms%n", tLiteAgg * 1000);
        System.out.printf("  DuckDB (reads only 2 columns' bytes) %9.1f ms%n", tDuckAgg * 1000);
        System.out.printf("  columnar speedup: %,.0fx%n", tLiteAgg / tDuckAgg);

        // --- The OLTP query: one whole row by primary key -------------------------
        int[] ids = sampleIds(200);
        PreparedStatement litePt = lite.prepareStatement(POINT);
        PreparedStatement duckPt = duck.prepareStatement(POINT);
        double tLitePt = bestOf(() -> pointReads(litePt, ids), 5) / ids.length;
        double tDuckPt = bestOf(() -> pointReads(duckPt, ids), 5) / ids.length;

        System.out.printf("%n--- OLTP: fetch one whole row by id (avg of %d random ids)%n", ids.length);
        System.out.printf("  H2 (B-tree walk to one page)              %9.1f us/row%n", tLitePt * 1e6);
        System.out.printf("  DuckDB (reassemble row from 8 col groups) %9.1f us/row%n", tDuckPt * 1e6);
        System.out.printf("  row-store advantage: %,.1fx%n", tDuckPt / tLitePt);

        System.out.println(
                "\nLesson: NEITHER store wins both queries. The layout that makes the scan\n"
              + "cheap makes the point read expensive, and vice versa — which is why OLTP\n"
              + "and OLAP are different store categories, fed from one source of truth.");
    }

    /** 200 distinct row ids, drawn from a fixed seed so every run reads the same rows. */
    static int[] sampleIds(int count) {
        Random rnd = new Random(42);
        Set<Integer> picked = new LinkedHashSet<>();
        while (picked.size() < count) picked.add(rnd.nextInt(N) + 1);
        int[] ids = new int[count];
        int i = 0;
        for (int id : picked) ids[i++] = id;
        return ids;
    }

    static void pointReads(PreparedStatement ps, int[] ids) throws SQLException {
        for (int id : ids) {
            ps.setInt(1, id);
            try (ResultSet r = ps.executeQuery()) {
                if (r.next()) sink += r.getLong(1);
            }
        }
    }

    static void drain(PreparedStatement ps) throws SQLException {
        try (ResultSet r = ps.executeQuery()) {
            while (r.next()) sink += r.getLong(1);
        }
    }

    static List<double[]> readAgg(PreparedStatement ps) throws SQLException {
        List<double[]> rows = new ArrayList<>();
        try (ResultSet r = ps.executeQuery()) {
            while (r.next()) rows.add(new double[] { r.getDouble(1), r.getDouble(2) });
        }
        return rows;
    }

    /** Guards against the two engines quietly answering two different questions. */
    static void requireSameAnswer(List<double[]> a, List<double[]> b) {
        boolean same = a.size() == b.size();
        for (int i = 0; same && i < a.size(); i++) {
            same = a.get(i)[0] == b.get(i)[0]
                    && Math.abs(a.get(i)[1] - b.get(i)[1]) <= 1e-6 * Math.abs(a.get(i)[1]);
        }
        if (!same) throw new IllegalStateException("engines disagree on the aggregate — timings are meaningless");
    }

    static double bestOf(Action fn, int repeat) throws SQLException {
        // WARM-UP passes first: without them the earliest timing in the program is a
        // measurement of the JIT compiler rather than of the storage engine, and for
        // the point reads that alone is an 8x difference.
        for (int i = 0; i < 3; i++) fn.run();
        double best = Double.MAX_VALUE;
        for (int i = 0; i < repeat; i++) {
            long t0 = System.nanoTime();
            fn.run();
            best = Math.min(best, (System.nanoTime() - t0) / 1e9);
        }
        return best;
    }

    @FunctionalInterface
    interface Action {
        void run() throws SQLException;
    }
}
