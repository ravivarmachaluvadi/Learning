import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Shared plumbing: fresh in-memory H2 databases, schema helpers, and a small
 * harness for running N clients in lockstep. No framework — every mechanism
 * in this lab is visible in plain JDBC.
 *
 * Run anything in this lab with:  java -cp ../lib/h2.jar src/<File>.java
 */
public class Db {

    private static int dbSeq = 0;

    /** A fresh, isolated in-memory database. DB_CLOSE_DELAY=-1 keeps it alive
     *  after the creating connection closes, so many connections can share it
     *  — which is the whole point: each simulated "request" gets its own
     *  connection and its own transaction, like a Spring app's pool. */
    public static synchronized String freshDb(String name) {
        return "jdbc:h2:mem:" + name + "_" + (dbSeq++) + ";DB_CLOSE_DELAY=-1";
    }

    public static Connection connect(String url) {
        try {
            Connection c = DriverManager.getConnection(url, "sa", "");
            c.setAutoCommit(false);
            return c;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** One product row shaped like a typical CRUD entity: several data columns
     *  plus ONE version column. The version column is the entire mechanism of
     *  optimistic locking — everything else in this lab is consequences. */
    public static void productSchema(String url) {
        try (Connection c = connect(url); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE product (id INT PRIMARY KEY, " +
                      "description VARCHAR NOT NULL, price INT NOT NULL, " +
                      "version BIGINT NOT NULL)");
            s.execute("INSERT INTO product VALUES " +
                      "(42, 'Steel water bottle, 500ml', 1500, 0)");
            c.commit();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public static void counterSchema(String url, int rows) {
        try (Connection c = connect(url); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE counter (id INT PRIMARY KEY, n INT NOT NULL, " +
                      "version BIGINT NOT NULL)");
            for (int i = 0; i < rows; i++)
                s.execute("INSERT INTO counter VALUES (" + i + ", 0, 0)");
            c.commit();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public static void jobSchema(String url, int jobs) {
        try (Connection c = connect(url); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE job (id INT PRIMARY KEY, " +
                      "status VARCHAR NOT NULL, worker VARCHAR)");
            for (int i = 0; i < jobs; i++)
                s.execute("INSERT INTO job VALUES (" + i + ", 'PENDING', NULL)");
            c.commit();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // ---- tiny concurrency harness ------------------------------------------

    /** Run n copies of task concurrently, starting on the same barrier,
     *  and wait for all. Task index is passed in. Exceptions propagate. */
    public static void race(int n, IntConsumer task) {
        CyclicBarrier start = new CyclicBarrier(n);
        List<Thread> threads = new ArrayList<>();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < n; i++) {
            final int idx = i;
            Thread t = new Thread(() -> {
                try { start.await(); task.accept(idx); }
                catch (Throwable e) { errors.add(e); }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            try { t.join(); } catch (InterruptedException e) { throw new RuntimeException(e); }
        }
        if (!errors.isEmpty()) throw new RuntimeException(errors.get(0));
    }

    public static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { throw new RuntimeException(e); }
    }

    /** Busy-work standing in for business logic between read and write.
     *  Real CPU work, so "redoing it" in a retry has a real, measurable cost. */
    public static long compute(long ms) {
        long deadline = System.nanoTime() + ms * 1_000_000;
        long x = 0;
        while (System.nanoTime() < deadline) x += 1;
        return x;
    }

    public static int queryInt(Connection c, String sql) {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getInt(1);
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public static String queryString(Connection c, String sql) {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getString(1);
        } catch (SQLException e) { throw new RuntimeException(e); }
    }
}
