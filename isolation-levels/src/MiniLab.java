import java.util.*;
import java.util.function.*;

/**
 * The same anomalies, run against the small engine in MiniDb — where you can see
 * the mechanism instead of only the outcome.
 *
 *   java src/MiniLab.java                 the matrix
 *   java src/MiniLab.java write-skew      every internal step, including the
 *                                         read/write arrows that SSI is tracking
 */
public class MiniLab {

    interface MAct { String run(MiniDb db, MiniDb.Tx tx, Map<String, String> obs); }
    record MStep(int txn, String label, MAct act) {}

    record MScenario(String key, String title, String story,
                     Consumer<MiniDb> seed, List<MStep> steps,
                     Function<MiniDb, String> finalState,
                     Predicate<Runner.Result> occurred) {
        boolean happened(Runner.Result r) { return r.bothCommitted() && occurred.test(r); }
    }

    // ---------------------------------------------------------------- the runner

    /**
     * Plays a script against MiniDb. No threads are needed: the engine says when a
     * step would have to wait, so waiting becomes something we can step over.
     *
     * Each transaction keeps a QUEUE of the steps it still owes. That matters: a
     * transaction blocked on its UPDATE has not skipped its COMMIT, it simply has
     * not got there yet, and it will the moment the lock is released.
     */
    static Runner.Result play(MScenario sc, MiniDb.Level level) {
        MiniDb db = new MiniDb();
        sc.seed().accept(db);
        return playOn(sc, level, db);
    }

    private static Runner.Result playOn(MScenario sc, MiniDb.Level level, MiniDb db) {
        Runner.Result res = new Runner.Result();
        MiniDb.Tx[] tx = { null, db.begin(level), db.begin(level) };
        List<Deque<Object[]>> q = List.of(new ArrayDeque<>(), new ArrayDeque<>(), new ArrayDeque<>());
        boolean[] dead = new boolean[3];

        List<MStep> steps = sc.steps();
        for (int i = 0; i < steps.size(); i++) {
            MStep s = steps.get(i);
            q.get(s.txn()).addLast(new Object[]{ s, i });
            pump(db, tx, q, dead, res, s.txn());
            pump(db, tx, q, dead, res, s.txn() == 1 ? 2 : 1);   // a commit may free the other side
        }

        // Nothing left to add. Keep pumping while progress is being made; if both
        // are stuck on each other, that is a deadlock and a real engine picks a victim.
        for (int round = 0; round < 8; round++) {
            boolean before = q.get(1).size() + q.get(2).size() > 0;
            int sizeBefore = q.get(1).size() + q.get(2).size();
            pump(db, tx, q, dead, res, 1);
            pump(db, tx, q, dead, res, 2);
            if (!before) break;
            if (q.get(1).size() + q.get(2).size() == sizeBefore) {
                if (!q.get(1).isEmpty() && !q.get(2).isEmpty()) {
                    db.note("deadlock: T1 is waiting for T2 and T2 is waiting for T1");
                    db.kill(tx[2], "chosen as the deadlock victim");
                    failRest(res, q.get(2), dead, 2, "40P01", "deadlock — this transaction was chosen as the victim");
                } else break;
            }
        }
        for (int t = 1; t <= 2; t++)
            if (!q.get(t).isEmpty()) failRest(res, q.get(t), dead, t, "40P01", "still waiting when the script ended");
        for (int t = 1; t <= 2; t++) if (tx[t].status == MiniDb.Status.ACTIVE) db.rollback(tx[t]);

        res.outcomes.sort(Comparator.comparingInt(Runner.Outcome::seq));
        res.finalState = sc.finalState().apply(db);
        return res;
    }

    /** Run as much of one transaction's queue as it can get through right now. */
    private static void pump(MiniDb db, MiniDb.Tx[] tx, List<Deque<Object[]>> q,
                             boolean[] dead, Runner.Result res, int t) {
        while (!q.get(t).isEmpty() && !dead[t]) {
            Object[] head = q.get(t).peek();
            MStep s = (MStep) head[0]; int i = (Integer) head[1];
            boolean waited = head.length > 2;
            try {
                String v = s.act().run(db, tx[t], res.obs);
                q.get(t).poll();
                Runner.record(res, i, s.label(), waited ? Runner.Kind.BLOCKED_THEN_OK : Runner.Kind.OK, v, null, null);
            } catch (MiniDb.Blocked b) {
                if (!waited) { db.note(tx[t].name() + " must wait: " + b.why); q.get(t).poll(); q.get(t).addFirst(new Object[]{ s, i, true }); }
                return;                                   // stuck for now; come back after the other side moves
            } catch (MiniDb.Abort a) {
                q.get(t).poll();
                Runner.record(res, i, s.label(), waited ? Runner.Kind.BLOCKED_THEN_ERROR : Runner.Kind.ERROR,
                              null, a.code, a.getMessage());
                dead[t] = true;
                q.get(t).clear();                         // a refused transaction runs nothing else
            }
        }
    }

    private static void failRest(Runner.Result res, Deque<Object[]> queue, boolean[] dead,
                                 int t, String code, String msg) {
        Object[] head = queue.peek();
        if (head != null) Runner.record(res, (Integer) head[1], ((MStep) head[0]).label(),
                                        Runner.Kind.BLOCKED_THEN_ERROR, null, code, msg);
        queue.clear(); dead[t] = true;
    }

    // ---------------------------------------------------------------- scenarios

    static final Predicate<Map.Entry<String, Integer>> RICH   = e -> e.getKey().startsWith("acct:") && e.getValue() > 50;
    static final Predicate<Map.Entry<String, Integer>> ON_CALL= e -> e.getKey().startsWith("doc:")  && e.getValue() == 1;
    static final Predicate<Map.Entry<String, Integer>> SLOT   = e -> e.getKey().startsWith("book:") && e.getValue() == 11;

    static Consumer<MiniDb> accounts() { return db -> { db.seed("acct:1", 100); db.seed("acct:2", 100); }; }

    static MStep read(int t, String label, String key) {
        return new MStep(t, label, (db, tx, o) -> String.valueOf(db.get(tx, key)));
    }
    static MStep count(int t, String label, String pl, Predicate<Map.Entry<String, Integer>> p) {
        return new MStep(t, label, (db, tx, o) -> String.valueOf(db.scan(tx, pl, p).size()));
    }
    static MStep write(int t, String label, String key, int v) {
        return new MStep(t, label, (db, tx, o) -> { db.put(tx, key, v); return "ok"; });
    }
    static MStep writeFrom(int t, String label, String key, String from, IntUnaryOperator f) {
        return new MStep(t, label, (db, tx, o) -> { db.put(tx, key, f.applyAsInt(Integer.parseInt(o.get(from)))); return "ok"; });
    }
    static MStep commit(int t) { return new MStep(t, "T" + t + " COMMIT", (db, tx, o) -> { db.commit(tx); return "committed"; }); }
    static MStep rollback(int t) { return new MStep(t, "T" + t + " ROLLBACK", (db, tx, o) -> { db.rollback(tx); return "rolled back"; }); }

    static List<MScenario> all() {
        return List.of(
            new MScenario("dirty-read", "Dirty read",
                "One transaction types a wrong balance, another reads it, and the first takes it back.",
                accounts(),
                List.of(write(1, "T1 set alice = 999", "acct:1", 999),
                        read(2, "T2 read alice", "acct:1"),
                        rollback(1), commit(2)),
                db -> String.valueOf(db.committed("acct:1")),
                r -> "999".equals(r.get("T2 read alice"))),

            new MScenario("dirty-write", "Dirty write",
                "Both transactions change Alice's balance; the first then takes it back.",
                accounts(),
                List.of(write(1, "T1 set alice = 110", "acct:1", 110),
                        write(2, "T2 set alice = 120", "acct:1", 120),
                        rollback(1), commit(2)),
                db -> String.valueOf(db.committed("acct:1")),
                r -> r.outcomes.stream().anyMatch(o -> o.label().equals("T2 set alice = 120") && o.kind() == Runner.Kind.OK)),

            new MScenario("non-repeatable-read", "Non-repeatable read",
                "One transaction reads Alice's balance twice, with someone else's committed change in between.",
                accounts(),
                List.of(read(1, "T1 read alice (first)", "acct:1"),
                        write(2, "T2 set alice = 200", "acct:1".intern(), 200),
                        commit(2),
                        read(1, "T1 read alice (again)", "acct:1"),
                        commit(1)),
                db -> String.valueOf(db.committed("acct:1")),
                r -> !Objects.equals(r.get("T1 read alice (first)"), r.get("T1 read alice (again)"))),

            new MScenario("phantom-read", "Phantom read",
                "One transaction counts the accounts over 50 twice; a new one is inserted in between.",
                accounts(),
                List.of(count(1, "T1 count rich (first)", "balance > 50", RICH),
                        write(2, "T2 insert carol", "acct:3", 500),
                        commit(2),
                        count(1, "T1 count rich (again)", "balance > 50", RICH),
                        commit(1)),
                db -> String.valueOf(db.countCommitted(RICH)),
                r -> !Objects.equals(r.get("T1 count rich (first)"), r.get("T1 count rich (again)"))),

            new MScenario("lost-update", "Lost update",
                "Two deposits of 10: each reads the balance, adds 10, writes it back.",
                accounts(),
                List.of(read(1, "T1 read alice", "acct:1"),
                        read(2, "T2 read alice", "acct:1"),
                        writeFrom(1, "T1 write read+10", "acct:1", "T1 read alice", v -> v + 10),
                        writeFrom(2, "T2 write read+10", "acct:1", "T2 read alice", v -> v + 10),
                        commit(1), commit(2)),
                db -> String.valueOf(db.committed("acct:1")),
                r -> "110".equals(r.finalState)),

            new MScenario("read-skew", "Read skew",
                "An audit reads Alice, then Bob, with a transfer committed in between.",
                accounts(),
                List.of(read(1, "T1 read alice", "acct:1"),
                        write(2, "T2 alice = 50", "acct:1", 50),
                        write(2, "T2 bob = 150", "acct:2", 150),
                        commit(2),
                        read(1, "T1 read bob", "acct:2"),
                        commit(1)),
                db -> String.valueOf(db.committed("acct:1") + db.committed("acct:2")),
                r -> r.intOf("T1 read alice") + r.intOf("T1 read bob") != 200),

            new MScenario("write-skew", "Write skew",
                "Two doctors each check that the other is on call, then sign off.",
                db -> { db.seed("doc:1", 1); db.seed("doc:2", 1); },
                List.of(count(1, "T1 count on call", "on_call", ON_CALL),
                        count(2, "T2 count on call", "on_call", ON_CALL),
                        write(1, "T1 alice signs off", "doc:1", 0),
                        write(2, "T2 bob signs off", "doc:2", 0),
                        commit(1), commit(2)),
                db -> String.valueOf(db.countCommitted(ON_CALL)),
                r -> "0".equals(r.finalState)),

            new MScenario("double-booking", "Write skew on rows that do not exist yet",
                "Two people check that room 1 is free on Monday, and both book it.",
                db -> { },
                List.of(count(1, "T1 slot free?", "room 1 monday", SLOT),
                        count(2, "T2 slot free?", "room 1 monday", SLOT),
                        write(1, "T1 books it", "book:101", 11),
                        write(2, "T2 books it", "book:102", 11),
                        commit(1), commit(2)),
                db -> String.valueOf(db.countCommitted(SLOT)),
                r -> "2".equals(r.finalState)));
    }

    // ---------------------------------------------------------------- main

    public static void main(String[] args) {
        System.out.println("\nMiniDb — the same anomalies, against an engine you can read\n");
        List<MScenario> scs = all();
        MiniDb.Level[] levels = MiniDb.Level.values();
        Map<String, Map<MiniDb.Level, Runner.Result>> detail = new LinkedHashMap<>();

        int w = scs.stream().mapToInt(s -> s.title().length()).max().orElse(20) + 2;
        System.out.printf("%-" + w + "s", "anomaly");
        for (MiniDb.Level l : levels) System.out.printf("%-18s", shortName(l));
        System.out.println();
        System.out.println("-".repeat(w + 18 * levels.length));
        for (MScenario sc : scs) {
            detail.put(sc.key(), new LinkedHashMap<>());
            System.out.printf("%-" + w + "s", sc.title());
            for (MiniDb.Level l : levels) {
                Runner.Result r = play(sc, l);
                detail.get(sc.key()).put(l, r);
                System.out.printf("%-18s", Runner.verdict(sc.happened(r), r));
            }
            System.out.println();
        }
        System.out.println("""

            READ UNCOMM.       no locks; you can see what nobody has committed
            READ COMMITTED     every statement reads the newest committed data
            SNAPSHOT           the transaction reads one frozen moment, and the
                               first committer wins any write-write race
            SERIALIZABLE (SSI) snapshot reads plus bookkeeping about what was READ
            SERIALIZABLE (2PL) no versions — locks, held until commit
            """);

        String want = args.length > 0 ? args[0] : null;
        if (want == null) {
            System.out.print("  For the internals of one anomaly:  java src/MiniLab.java ");
            System.out.println(scs.get(5).key());
            System.out.println();
            return;
        }
        for (MScenario sc : scs) {
            if (!sc.key().equals(want) && !want.equals("all")) continue;
            for (MiniDb.Level l : levels) {
                System.out.println("=".repeat(78));
                System.out.println(sc.title() + "  under  " + shortName(l));
                System.out.println("=".repeat(78));
                System.out.println("  " + sc.story() + "\n");
                MiniDb db = new MiniDb();
                sc.seed().accept(db);
                Runner.Result r = playOn(sc, l, db);
                for (String line : db.log) System.out.println("    " + line);
                System.out.println("\n  outcome: " + Runner.verdict(sc.happened(r), r)
                    + "   final state: " + r.finalState);
                for (Runner.Outcome o : r.outcomes)
                    if (o.kind() == Runner.Kind.ERROR || o.kind() == Runner.Kind.BLOCKED_THEN_ERROR)
                        System.out.println("  refused: [" + o.sqlState() + "] " + o.message());
                System.out.println();
            }
        }
    }

    static String shortName(MiniDb.Level l) {
        return switch (l) {
            case READ_UNCOMMITTED -> "READ UNCOMM."; case READ_COMMITTED -> "READ COMMITTED"; case SNAPSHOT -> "SNAPSHOT";
            case SERIALIZABLE_SSI -> "SERIALIZABLE(SSI)"; case SERIALIZABLE_2PL -> "SERIALIZABLE(2PL)";
        };
    }
}
