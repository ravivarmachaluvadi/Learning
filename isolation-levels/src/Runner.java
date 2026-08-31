import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plays a scripted interleaving of two transactions against a real database.
 *
 * The hard part is not running the statements — it is noticing when one of them
 * WAITS. A transaction blocked on a row lock has not failed and has not
 * succeeded; it is simply stopped, and it will finish the moment the other
 * transaction commits. So each step gets its own thread and a short deadline: if
 * the deadline passes, the step is recorded as BLOCKED and the script moves on
 * to the other transaction, which is exactly what unblocks it.
 */
final class Runner {

    /** What one step did. */
    enum Kind { OK, BLOCKED_THEN_OK, BLOCKED_THEN_ERROR, ERROR }

    record Outcome(int seq, String label, Kind kind, String value, String sqlState, String message) {
        String render() {
            return switch (kind) {
                case OK                 -> label + " -> " + value;
                case BLOCKED_THEN_OK    -> label + " -> waited for the other transaction, then " + value;
                case ERROR              -> label + " -> REFUSED [" + sqlState + "] " + message;
                case BLOCKED_THEN_ERROR -> label + " -> waited, then REFUSED [" + sqlState + "] " + message;
            };
        }
    }

    static final class Result {
        final List<Outcome> outcomes = new ArrayList<>();
        /** True only if neither transaction was refused — an anomaly is a property of a COMMITTED history. */
        boolean bothCommitted() { return !anyError(); }
        final Map<String, String> obs = new LinkedHashMap<>();
        String finalState = "";
        boolean anyError() { return outcomes.stream().anyMatch(o -> o.kind == Kind.ERROR || o.kind == Kind.BLOCKED_THEN_ERROR); }
        boolean anyBlocked() { return outcomes.stream().anyMatch(o -> o.kind == Kind.BLOCKED_THEN_OK || o.kind == Kind.BLOCKED_THEN_ERROR); }
        String firstErrorState() {
            return outcomes.stream().filter(o -> o.sqlState != null).map(Outcome::sqlState).findFirst().orElse(null);
        }
        String get(String label) { return obs.get(label); }
        int intOf(String label) { try { return Integer.parseInt(obs.get(label)); } catch (Exception e) { return Integer.MIN_VALUE; } }
    }

    /** How long a step may take before we call it blocked rather than slow. */
    static long stepTimeoutMs = 600;
    /** How long we then give every outstanding step, once the whole script has been issued. */
    static long resolveTimeoutMs = 8_000;

    static final String SKIPPED = "\u0000skipped";

    private final int level;
    private final Connection[] conn = new Connection[3];        // index 1 and 2
    private final ExecutorService[] exec = new ExecutorService[3];
    private final AtomicBoolean[] dead = { null, new AtomicBoolean(), new AtomicBoolean() };
    private final boolean[] waiting = new boolean[3];           // does this transaction already owe a step?

    Runner(int level) { this.level = level; }

    Result play(Scenario sc) throws Exception {
        Result res = new Result();
        Map<String, String> obs = new ConcurrentHashMap<>();
        try (Connection admin = Db.open()) {
            Db.relaxLockTimeout(admin);
            Db.exec(admin, sc.ddl());
            admin.commit();
        }
        try {
            for (int t = 1; t <= 2; t++) {
                conn[t] = Db.open();
                conn[t].setTransactionIsolation(level);
                Db.relaxLockTimeout(conn[t]);
                exec[t] = Executors.newSingleThreadExecutor(r -> { Thread th = new Thread(r); th.setDaemon(true); return th; });
            }

            List<Step> steps = sc.steps();
            List<Future<String>> futures = new ArrayList<>();

            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                int t = step.txn();
                // Submit at the script position, always. A step whose transaction is
                // already waiting simply queues behind it — which is exactly what
                // happens to a real client that keeps sending on a stalled connection.
                Future<String> f = exec[t].submit(task(step, t, obs));
                futures.add(f);
                if (waiting[t]) continue;                       // do not stall the script on a queued step
                try { f.get(stepTimeoutMs, TimeUnit.MILLISECONDS); }
                catch (TimeoutException e) { waiting[t] = true; }   // blocked: carry on with the other transaction
                catch (ExecutionException | InterruptedException e) { /* collected below */ }
            }

            // Everything has been issued. Now give each outstanding step its time.
            for (int i = 0; i < futures.size(); i++) {
                Step step = steps.get(i);
                Future<String> f = futures.get(i);
                boolean waited = !f.isDone();
                try {
                    String v = f.get(resolveTimeoutMs, TimeUnit.MILLISECONDS);
                    if (SKIPPED.equals(v)) continue;            // its transaction was already refused
                    record(res, i, step.label(), waited ? Kind.BLOCKED_THEN_OK : Kind.OK, v, null, null);
                } catch (TimeoutException e) {
                    f.cancel(true);
                    record(res, i, step.label(), Kind.BLOCKED_THEN_ERROR, null, "40P01",
                           "still waiting after " + resolveTimeoutMs + "ms — deadlock");
                    try { conn[step.txn()].close(); } catch (SQLException ignored) {}
                } catch (ExecutionException e) {
                    SQLException se = as(e);
                    record(res, i, step.label(), waited ? Kind.BLOCKED_THEN_ERROR : Kind.ERROR,
                           null, se == null ? null : se.getSQLState(), brief(e));
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            res.obs.putAll(obs);
            res.outcomes.sort(Comparator.comparingInt(Outcome::seq));

            // Anything still open is rolled back, so the final read sees only committed data.
            for (int t = 1; t <= 2; t++) { try { conn[t].rollback(); } catch (SQLException ignored) {} }

            try (Connection admin = Db.open()) {
                res.finalState = Db.one(admin, sc.finalQuery());
                admin.commit();
            }
            return res;
        } finally {
            for (int t = 1; t <= 2; t++) {
                if (exec[t] != null) exec[t].shutdownNow();
                if (conn[t] != null) try { conn[t].close(); } catch (SQLException ignored) {}
            }
        }
    }

    /**
     * The step, wrapped so that a transaction which has already been refused runs
     * nothing else, and so that what a step OBSERVED is published as soon as it
     * happens — a later step may be built from it while the driver is elsewhere.
     */
    private Callable<String> task(Step step, int t, Map<String, String> obs) {
        return () -> {
            if (dead[t].get()) return SKIPPED;
            try {
                String v = step.act().run(conn[t], obs);
                if (v != null) obs.put(step.label(), v);
                return v;
            } catch (SQLException e) { dead[t].set(true); throw e; }
        };
    }

    static void record(Result res, int seq, String label, Kind k, String v, String state, String msg) {
        res.outcomes.add(new Outcome(seq, label, k, v, state, msg));
        if (v != null) res.obs.put(label, v);
    }

    /** The four-way verdict used by both matrices. */
    static String verdict(boolean happened, Result r) {
        if (happened)       return "ALLOWED";
        if (r.anyError())   return "safe(err)";
        if (r.anyBlocked()) return "safe(wait)";
        return "safe";
    }

    private static SQLException as(ExecutionException e) {
        Throwable c = e.getCause();
        return c instanceof SQLException s ? s : null;
    }
    private static String brief(ExecutionException e) {
        String m = e.getCause() == null ? e.toString() : e.getCause().getMessage();
        if (m == null) return "";
        m = m.split("\n")[0];
        return m.length() > 110 ? m.substring(0, 110) + "…" : m;
    }
}
