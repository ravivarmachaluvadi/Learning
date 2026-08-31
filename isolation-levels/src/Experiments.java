import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * Measured experiments — real threads, real contention, real numbers.
 *   java -cp ../lib/h2.jar src/Experiments.java <name>
 */
public class Experiments {

    static final int THREADS = 8, PER_THREAD = 40;

    public static void main(String[] a) throws Exception {
        String which = a.length > 0 ? a[0] : "";
        switch (which) {
            case "increment"  -> increment();
            case "contention" -> contention();
            case "skew"       -> skew();
            case "stability"  -> stability();
            case "versions"   -> versions();
            case "deadlock"   -> deadlock();
            case "all"        -> { increment(); contention(); skew(); stability(); versions(); deadlock(); }
            default -> System.out.println("""

                Experiments — java -cp ../lib/h2.jar src/Experiments.java <name>

                  increment   four ways to add 1 to a row, at three isolation levels
                  contention  how the refusal rate grows as rows get hotter
                  skew        how often write skew actually happens, unscripted
                  stability   what a long-running report sees while writers work
                  versions    what a long transaction costs the storage engine
                  deadlock    lock ordering, and the one-line fix
                  all         every experiment
                """);
        }
    }

    static void head(String t) { System.out.println("\n" + t + "\n" + "=".repeat(t.length()) + "\n"); }

    static void reset(String... sql) throws SQLException {
        try (Connection c = Db.open()) { Db.exec(c, sql); c.commit(); }
    }

    static final String[] ONE_ROW = {
        "DROP TABLE IF EXISTS ctr", "CREATE TABLE ctr(id INT PRIMARY KEY, n INT)", "INSERT INTO ctr VALUES (1, 0)" };

    // ------------------------------------------------------------------ 1
    enum How { READ_THEN_WRITE, ATOMIC_SQL, SELECT_FOR_UPDATE }

    static void increment() throws Exception {
        head("1. Adding 1 to the same row, " + THREADS + " threads x " + PER_THREAD + " times  (expected " + THREADS * PER_THREAD + ")");
        System.out.printf("  %-22s %-38s %-38s %s%n", "", "READ COMMITTED", "REPEATABLE READ (snapshot)", "SERIALIZABLE");
        for (How how : How.values()) {
            System.out.printf("  %-22s", label(how));
            for (int lvl : new int[]{ Connection.TRANSACTION_READ_COMMITTED,
                                      Connection.TRANSACTION_REPEATABLE_READ,
                                      Connection.TRANSACTION_SERIALIZABLE }) {
                reset(ONE_ROW);
                Retry.Stats st = new Retry.Stats();
                long t0 = System.nanoTime();
                runThreads(THREADS, () -> {
                    for (int i = 0; i < PER_THREAD; i++) {
                        try { Retry.inTransaction(lvl, 200, st, c -> { bump(c, how, 1); return null; }); }
                        catch (SQLException e) { throw new RuntimeException(e); }
                    }
                });
                long ms = (System.nanoTime() - t0) / 1_000_000;
                int got;
                try (Connection c = Db.open()) { got = Integer.parseInt(Db.one(c, "SELECT n FROM ctr WHERE id = 1")); }
                String flag = got == THREADS * PER_THREAD ? "" : " LOST " + (THREADS * PER_THREAD - got);
                System.out.printf("%-38s", got + "/" + (THREADS * PER_THREAD) + flag + "  (" + st.retries + " retries, " + ms + "ms)");
            }
            System.out.println();
        }
        System.out.println("""

              Read the first column first. READ COMMITTED never refuses anything, so
              the retry loop never fires — and read-then-write silently loses updates
              anyway. Doing the arithmetic in SQL fixes it at every level, with no
              retries at all, because the database re-reads the row after it gets the
              lock. That single line is the cheapest correct answer in this lab.""");
    }

    static String label(How h) {
        return switch (h) {
            case READ_THEN_WRITE   -> "SELECT then UPDATE";
            case ATOMIC_SQL        -> "UPDATE n = n + 1";
            case SELECT_FOR_UPDATE -> "SELECT FOR UPDATE";
        };
    }

    static void bump(Connection c, How how, int id) throws SQLException {
        switch (how) {
            case ATOMIC_SQL -> Db.exec(c, "UPDATE ctr SET n = n + 1 WHERE id = " + id);
            case READ_THEN_WRITE -> {
                int v = Integer.parseInt(Db.one(c, "SELECT n FROM ctr WHERE id = " + id));
                Db.exec(c, "UPDATE ctr SET n = " + (v + 1) + " WHERE id = " + id);
            }
            case SELECT_FOR_UPDATE -> {
                int v = Integer.parseInt(Db.one(c, "SELECT n FROM ctr WHERE id = " + id + " FOR UPDATE"));
                Db.exec(c, "UPDATE ctr SET n = " + (v + 1) + " WHERE id = " + id);
            }
        }
    }

    // ------------------------------------------------------------------ 2
    static void contention() throws Exception {
        head("2. The same work, spread over more rows  (REPEATABLE READ, SELECT then UPDATE)");
        System.out.printf("  %-14s %-14s %-14s %s%n", "hot rows", "total", "retries", "time");
        for (int rows : new int[]{ 1, 2, 4, 16, 64 }) {
            String[] ddl = new String[rows + 2];
            ddl[0] = "DROP TABLE IF EXISTS ctr"; ddl[1] = "CREATE TABLE ctr(id INT PRIMARY KEY, n INT)";
            for (int i = 0; i < rows; i++) ddl[i + 2] = "INSERT INTO ctr VALUES (" + (i + 1) + ", 0)";
            reset(ddl);
            Retry.Stats st = new Retry.Stats();
            AtomicInteger seq = new AtomicInteger();
            long t0 = System.nanoTime();
            runThreads(THREADS, () -> {
                for (int i = 0; i < PER_THREAD; i++) {
                    int id = seq.getAndIncrement() % rows + 1;
                    try { Retry.inTransaction(Connection.TRANSACTION_REPEATABLE_READ, 300, st,
                            c -> { bump(c, How.READ_THEN_WRITE, id); return null; }); }
                    catch (SQLException e) { throw new RuntimeException(e); }
                }
            });
            long ms = (System.nanoTime() - t0) / 1_000_000;
            int total;
            try (Connection c = Db.open()) { total = Integer.parseInt(Db.one(c, "SELECT SUM(n) FROM ctr")); }
            System.out.printf("  %-14d %-14s %-14d %d ms%n", rows,
                total + "/" + (THREADS * PER_THREAD), st.retries, ms);
        }
        System.out.println("""

              Nothing about the isolation level changed. Contention is a property of
              your DATA ACCESS PATTERN, and the refusal rate is what it feels like.
              This is why 'we turned on serializable and the app fell over' is nearly
              always a hot-row problem wearing an isolation-level costume.""");
    }

    // ------------------------------------------------------------------ 3
    static void skew() throws Exception {
        head("3. Write skew, unscripted: two threads racing to sign off, 200 rounds per level");
        System.out.printf("  %-24s %-16s %s%n", "isolation level", "rounds broken", "of 200");
        for (int lvl : Db.LEVELS) {
            int broken = 0;
            for (int round = 0; round < 200; round++) {
                reset("DROP TABLE IF EXISTS doctors",
                      "CREATE TABLE doctors(id INT PRIMARY KEY, on_call BOOLEAN)",
                      "INSERT INTO doctors VALUES (1, TRUE)", "INSERT INTO doctors VALUES (2, TRUE)");
                final int L = lvl;
                runThreads(2, i -> {
                    try {
                        Retry.inTransaction(L, 5, null, c -> {
                            int n = Integer.parseInt(Db.one(c, "SELECT COUNT(*) FROM doctors WHERE on_call = TRUE"));
                            if (n >= 2) Db.exec(c, "UPDATE doctors SET on_call = FALSE WHERE id = " + (i + 1));
                            return null;
                        });
                    } catch (SQLException ignored) { }
                });
                try (Connection c = Db.open()) {
                    if ("0".equals(Db.one(c, "SELECT COUNT(*) FROM doctors WHERE on_call = TRUE"))) broken++;
                }
            }
            System.out.printf("  %-24s %-16d %s%n", Db.name(lvl), broken,
                broken == 0 ? "(never lined up in this run — try more rounds)" : "");
        }
        System.out.println("""

              Every level this engine offers, including the one called SERIALIZABLE,
              lets both doctors sign off. The rule was never written down anywhere the
              database could see it — it lived in an IF statement in the application.
              For the level that DOES stop this, run:  java src/MiniLab.java write-skew""");
    }

    // ------------------------------------------------------------------ 4
    static void stability() throws Exception {
        head("4. A report reading 4 accounts repeatedly while transfers run  (money must total 400)");
        System.out.printf("  %-24s %-22s %s%n", "isolation level", "wrong totals seen", "of 300 reads");
        for (int lvl : new int[]{ Connection.TRANSACTION_READ_COMMITTED,
                                  Connection.TRANSACTION_REPEATABLE_READ }) {
            reset("DROP TABLE IF EXISTS acct", "CREATE TABLE acct(id INT PRIMARY KEY, balance INT)",
                  "INSERT INTO acct VALUES (1,100)", "INSERT INTO acct VALUES (2,100)",
                  "INSERT INTO acct VALUES (3,100)", "INSERT INTO acct VALUES (4,100)");
            AtomicBoolean stop = new AtomicBoolean();
            AtomicInteger wrong = new AtomicInteger(), reads = new AtomicInteger();
            Thread writer = Thread.ofVirtual().start(() -> {
                Random rnd = new Random(1);
                while (!stop.get()) {
                    int from = rnd.nextInt(4) + 1, to = rnd.nextInt(4) + 1;
                    if (from == to) continue;
                    try { Retry.inTransaction(Connection.TRANSACTION_READ_COMMITTED, 50, null, c -> {
                            Db.exec(c, "UPDATE acct SET balance = balance - 10 WHERE id = " + from,
                                       "UPDATE acct SET balance = balance + 10 WHERE id = " + to);
                            return null; }); }
                    catch (SQLException ignored) { }
                }
            });
            for (int i = 0; i < 300; i++) {
                try (Connection c = Db.open()) {
                    c.setTransactionIsolation(lvl);
                    int total = 0;
                    // read the four rows one statement at a time, as a report would
                    for (int id = 1; id <= 4; id++) total += Integer.parseInt(Db.one(c, "SELECT balance FROM acct WHERE id = " + id));
                    c.commit();
                    reads.incrementAndGet();
                    if (total != 400) wrong.incrementAndGet();
                } catch (SQLException ignored) { }
            }
            stop.set(true); writer.join();
            System.out.printf("  %-24s %-22d %d%n", Db.name(lvl), wrong.get(), reads.get());
        }
        System.out.println("""

              Four separate SELECTs, no writes at all, and at READ COMMITTED the report
              regularly adds up to something that was never true. Nothing was locked,
              nothing errored, and the report is wrong. A snapshot makes the four reads
              one consistent picture — this is the everyday reason to raise the level.""");
    }

    // ------------------------------------------------------------------ 5
    static void versions() {
        head("5. What a long-running transaction costs the storage engine");
        MiniDb db = new MiniDb();
        db.seed("acct:1", 100);
        MiniDb.Tx reader = db.begin(MiniDb.Level.SNAPSHOT);
        System.out.println("  A report opens a snapshot and then goes to lunch.\n");
        System.out.println("  " + db.chain("acct:1"));
        for (int i = 1; i <= 5; i++) {
            MiniDb.Tx w = db.begin(MiniDb.Level.READ_COMMITTED);
            db.put(w, "acct:1", 100 + i * 10);
            db.commit(w);
            System.out.printf("  after write %d: %s%n", i, db.chain("acct:1"));
        }
        System.out.println("\n  the report still reads: " + db.get(reader, "acct:1")
            + "   (its snapshot is older than every one of those versions)");
        db.commit(reader);
        System.out.println("""

              Every one of those old versions has to be kept while ANY transaction might
              still need it. That is the hidden price of snapshot reads, and in Postgres
              it is what VACUUM exists to clean up: one forgotten open transaction stops
              cleanup for the whole database, and the table grows without the row count
              changing. `idle_in_transaction_session_timeout` exists for this reason.""");
    }

    // ------------------------------------------------------------------ 6
    static void deadlock() throws Exception {
        head("6. Two transfers in opposite directions");
        for (boolean ordered : new boolean[]{ false, true }) {
            reset("DROP TABLE IF EXISTS acct", "CREATE TABLE acct(id INT PRIMARY KEY, balance INT)",
                  "INSERT INTO acct VALUES (1,100)", "INSERT INTO acct VALUES (2,100)");
            AtomicInteger deadlocks = new AtomicInteger();
            Retry.Stats st = new Retry.Stats();
            for (int round = 0; round < 60; round++) {
                runThreads(2, i -> {
                    int from = i == 0 ? 1 : 2, to = i == 0 ? 2 : 1;
                    int first = ordered ? Math.min(from, to) : from;
                    int second = ordered ? Math.max(from, to) : to;
                    try {
                        Retry.inTransaction(Connection.TRANSACTION_READ_COMMITTED, 30, st, c -> {
                            Db.one(c, "SELECT balance FROM acct WHERE id = " + first + " FOR UPDATE");
                            Db.one(c, "SELECT balance FROM acct WHERE id = " + second + " FOR UPDATE");
                            Db.exec(c, "UPDATE acct SET balance = balance - 1 WHERE id = " + from,
                                       "UPDATE acct SET balance = balance + 1 WHERE id = " + to);
                            return null;
                        });
                    } catch (SQLException e) { deadlocks.incrementAndGet(); }
                });
            }
            System.out.printf("  lock order: %-28s retries: %-6d gave up: %d%n",
                ordered ? "always lowest id first" : "whatever the transfer needs", st.retries, deadlocks.get());
        }
        System.out.println("""

              Same work, same isolation level. Taking the two locks in a fixed order
              means no pair of transactions can ever hold what the other one wants, so
              the cycle cannot form. Deadlocks are a LOCK ORDERING bug, not an isolation
              level problem, and this is the fix that does not cost throughput.""");
    }

    // ------------------------------------------------------------------ helpers
    interface Body { void run(int index); }

    static void runThreads(int n, Runnable body) throws InterruptedException { runThreads(n, i -> body.run()); }

    static void runThreads(int n, Body body) throws InterruptedException {
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < n; i++) { int id = i; ts.add(Thread.ofVirtual().unstarted(() -> body.run(id))); }
        ts.forEach(Thread::start);
        for (Thread t : ts) t.join();
    }
}
