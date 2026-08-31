import java.sql.*;
import java.util.*;

/**
 * Every claim this lab makes, as an assertion. Run: java -cp ../lib/h2.jar src/Tests.java
 *
 * The matrix rows are the lab's factual content, so they are locked in here: if a
 * future H2 changes its behaviour, or the harness regresses, this fails loudly
 * rather than the README quietly becoming wrong.
 */
public class Tests {
    static int pass = 0, fail = 0;

    static void check(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  PASS  " + name); }
        else      { fail++; System.out.println("  FAIL  " + name); }
    }
    static void eq(String name, Object expected, Object actual) {
        boolean ok = Objects.equals(String.valueOf(expected), String.valueOf(actual));
        if (ok) { pass++; System.out.println("  PASS  " + name); }
        else    { fail++; System.out.println("  FAIL  " + name + "   expected <" + expected + "> but was <" + actual + ">"); }
    }

    /** columns: RU, RC, RR, SNAPSHOT, SERIALIZABLE — measured against H2 2.4.240. */
    static final Map<String, String[]> H2_EXPECTED = new LinkedHashMap<>(Map.of(
        "dirty-write",            new String[]{"ALLOWED",    "safe(wait)", "safe(wait)", "safe(wait)", "safe(wait)"},
        "dirty-read",             new String[]{"ALLOWED",    "safe",       "safe",       "safe",       "safe"},
        "non-repeatable-read",    new String[]{"ALLOWED",    "ALLOWED",    "safe",       "safe",       "safe"},
        "phantom-read",           new String[]{"ALLOWED",    "ALLOWED",    "safe",       "safe",       "safe"},
        "lost-update",            new String[]{"ALLOWED",    "ALLOWED",    "safe(err)",  "safe(err)",  "safe(err)"},
        "lost-update-atomic",     new String[]{"safe(wait)", "safe(wait)", "safe(err)",  "safe(err)",  "safe(err)"},
        "lost-update-for-update", new String[]{"safe(wait)", "safe(wait)", "safe(err)",  "safe(err)",  "safe(err)"},
        "read-skew",              new String[]{"ALLOWED",    "ALLOWED",    "safe",       "safe",       "safe"},
        "write-skew",             new String[]{"ALLOWED",    "ALLOWED",    "ALLOWED",    "ALLOWED",    "ALLOWED"},
        "double-booking",         new String[]{"ALLOWED",    "ALLOWED",    "ALLOWED",    "ALLOWED",    "ALLOWED"}));

    /** columns: READ UNCOMMITTED, READ COMMITTED, SNAPSHOT, SERIALIZABLE(SSI), SERIALIZABLE(2PL). */
    static final Map<String, String[]> MINI_EXPECTED = new LinkedHashMap<>(Map.of(
        "dirty-read",          new String[]{"ALLOWED", "safe",       "safe",       "safe",      "safe(wait)"},
        "dirty-write",         new String[]{"ALLOWED", "safe(wait)", "safe(wait)", "safe(wait)","safe(wait)"},
        "non-repeatable-read", new String[]{"ALLOWED", "ALLOWED",    "safe",       "safe",      "safe(wait)"},
        "phantom-read",        new String[]{"ALLOWED", "ALLOWED",    "safe",       "safe",      "safe(wait)"},
        "lost-update",         new String[]{"ALLOWED", "ALLOWED",    "safe(err)",  "safe(err)", "safe(err)"},
        "read-skew",           new String[]{"ALLOWED", "ALLOWED",    "safe",       "safe",      "safe(wait)"},
        "write-skew",          new String[]{"ALLOWED", "ALLOWED",    "ALLOWED",    "safe(err)", "safe(err)"},
        "double-booking",      new String[]{"ALLOWED", "ALLOWED",    "ALLOWED",    "safe(err)", "safe(err)"}));

    public static void main(String[] args) throws Exception {
        System.out.println("\n=== 1. the real database (" + Db.product() + ") ===\n");
        for (Scenario sc : Anomalies.all()) {
            String[] want = H2_EXPECTED.get(sc.key());
            if (want == null) continue;
            for (int i = 0; i < Db.LEVELS.length; i++) {
                Runner.Result r = new Runner(Db.LEVELS[i]).play(sc);
                eq(sc.key() + " @ " + Db.shortName(Db.LEVELS[i]), want[i], Runner.verdict(sc.happened(r), r));
            }
        }

        System.out.println("\n=== 2. the engine we can read ===\n");
        for (MiniLab.MScenario sc : MiniLab.all()) {
            String[] want = MINI_EXPECTED.get(sc.key());
            if (want == null) continue;
            MiniDb.Level[] levels = MiniDb.Level.values();
            for (int i = 0; i < levels.length; i++) {
                Runner.Result r = MiniLab.play(sc, levels[i]);
                eq(sc.key() + " @ " + levels[i], want[i], Runner.verdict(sc.happened(r), r));
            }
        }

        System.out.println("\n=== 3. the mechanisms, one at a time ===\n");

        // a snapshot really does freeze time
        MiniDb db = new MiniDb(); db.seed("k", 1);
        MiniDb.Tx snap = db.begin(MiniDb.Level.SNAPSHOT);
        MiniDb.Tx rc   = db.begin(MiniDb.Level.READ_COMMITTED);
        MiniDb.Tx w    = db.begin(MiniDb.Level.READ_COMMITTED);
        db.put(w, "k", 2); db.commit(w);
        eq("snapshot_reader_still_sees_the_old_value", 1, db.get(snap, "k"));
        eq("read_committed_reader_sees_the_new_value", 2, db.get(rc, "k"));
        db.commit(snap); db.commit(rc);

        // you always see your own uncommitted writes
        db = new MiniDb(); db.seed("k", 1);
        MiniDb.Tx me = db.begin(MiniDb.Level.SNAPSHOT);
        db.put(me, "k", 9);
        eq("a_transaction_sees_its_own_writes", 9, db.get(me, "k"));
        MiniDb.Tx other = db.begin(MiniDb.Level.READ_COMMITTED);
        eq("but_nobody_else_does_until_commit", 1, db.get(other, "k"));
        db.rollback(me); db.rollback(other);

        // first committer wins
        db = new MiniDb(); db.seed("k", 1);
        MiniDb.Tx a = db.begin(MiniDb.Level.SNAPSHOT), b = db.begin(MiniDb.Level.SNAPSHOT);
        db.put(a, "k", 2); db.commit(a);
        db.put(b, "k", 3);
        String code = null;
        try { db.commit(b); } catch (MiniDb.Abort e) { code = e.code; }
        eq("second_committer_is_refused_with_40001", "40001", code);
        eq("and_the_first_committers_value_stands", 2, db.committed("k"));

        // SSI aborts the pivot, and only the pivot
        Runner.Result ssi = MiniLab.play(byKey("write-skew"), MiniDb.Level.SERIALIZABLE_SSI);
        eq("ssi_leaves_exactly_one_doctor_on_call", "1", ssi.finalState);
        check("ssi_refuses_exactly_one_transaction",
              ssi.outcomes.stream().filter(o -> o.sqlState() != null).count() == 1);
        check("ssi_refusal_is_a_serialization_failure",
              ssi.outcomes.stream().anyMatch(o -> "40001".equals(o.sqlState())));

        // 2PL reaches the same answer by deadlocking and picking a victim
        Runner.Result twopl = MiniLab.play(byKey("write-skew"), MiniDb.Level.SERIALIZABLE_2PL);
        eq("2pl_also_leaves_one_doctor_on_call", "1", twopl.finalState);
        check("2pl_refusal_is_a_deadlock",
              twopl.outcomes.stream().anyMatch(o -> "40P01".equals(o.sqlState())));

        // snapshot isolation genuinely cannot see write skew
        Runner.Result si = MiniLab.play(byKey("write-skew"), MiniDb.Level.SNAPSHOT);
        eq("snapshot_isolation_leaves_nobody_on_call", "0", si.finalState);
        check("and_raises_no_error_at_all", !si.anyError());

        // every write makes a version; none is overwritten
        db = new MiniDb(); db.seed("k", 1);
        for (int i = 2; i <= 4; i++) { MiniDb.Tx t = db.begin(MiniDb.Level.READ_COMMITTED); db.put(t, "k", i); db.commit(t); }
        check("four_writes_leave_four_versions", db.chain("k").split("\\[").length - 1 == 4);

        System.out.println("\n=== 4. the retry rule ===\n");
        check("40001_is_retryable",   Retry.isRetryable(new SQLException("x", "40001")));
        check("40P01_is_retryable",   Retry.isRetryable(new SQLException("x", "40P01")));
        check("23505_is_not",        !Retry.isRetryable(new SQLException("dup key", "23505")));
        check("null_state_is_not",   !Retry.isRetryable(new SQLException("boom")));

        System.out.printf("%n%d passed, %d failed%n", pass, fail);
        if (fail > 0) {
            System.out.println("""

                If a cell differs only between safe / safe(wait) / safe(err), the harness
                may be mistaking a slow step for a blocked one on a loaded machine — raise
                Runner.stepTimeoutMs. A cell flipping to or from ALLOWED is a real change
                in behaviour, and is not timing-dependent.""");
            System.exit(1);
        }
    }

    static MiniLab.MScenario byKey(String key) {
        return MiniLab.all().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
    }
}
