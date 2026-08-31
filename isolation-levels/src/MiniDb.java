import java.util.*;
import java.util.function.*;

/**
 * A database small enough to read in one sitting, built to answer the question
 * the real one cannot: WHY does each isolation level behave the way it does?
 *
 * It stores one integer per key, and every write creates a new VERSION stamped
 * with the moment it became visible. Nothing is ever overwritten in place, which
 * is what lets two transactions read different pasts at the same time.
 *
 * Four modes, three genuinely different mechanisms:
 *   READ_UNCOMMITTED  no locks, and you can see what nobody has committed
 *   READ_COMMITTED  every statement reads the newest committed version
 *   SNAPSHOT        the whole transaction reads one frozen moment
 *   SERIALIZABLE_SSI  snapshot reads, plus bookkeeping about what you READ
 *   SERIALIZABLE_2PL  no versions at all — locks, held until commit
 *
 */
final class MiniDb {

    enum Level { READ_UNCOMMITTED, READ_COMMITTED, SNAPSHOT, SERIALIZABLE_SSI, SERIALIZABLE_2PL }
    enum Status { ACTIVE, COMMITTED, ABORTED }

    /** A step that must wait for another transaction. Not an error — a pause. */
    static final class Blocked extends RuntimeException {
        final String why;
        Blocked(String why) { super(why, null, false, false); this.why = why; }
    }
    /** The database refusing a transaction. The application is expected to retry. */
    static final class Abort extends RuntimeException {
        final String code;
        Abort(String code, String msg) { super(msg, null, false, false); this.code = code; }
    }

    /** One value of one key, alive from `begin` until `end`. `end == MAX` means current. */
    private record Version(int value, boolean deleted, long begin, long end) {}

    static final class Tx {
        final long id; final Level level; final long startTs;
        long commitTs = 0;
        Status status = Status.ACTIVE;
        final Map<String, Integer> writes = new LinkedHashMap<>();   // buffered; null value = delete
        final Set<String> readKeys = new LinkedHashSet<>();
        final List<Pred> readPreds = new ArrayList<>();
        // SSI arrows. rwOut: transactions that overwrote something I read (I must
        // come before them). rwIn: transactions whose reads I overwrote.
        final Set<Long> rwOut = new LinkedHashSet<>(), rwIn = new LinkedHashSet<>();
        final Set<String> shared = new LinkedHashSet<>(), exclusive = new LinkedHashSet<>();
        boolean tableShared;                                         // 2PL: a predicate read locks the table
        Tx(long id, Level level, long startTs) { this.id = id; this.level = level; this.startTs = startTs; }
        String name() { return "T" + id; }
    }

    /** A remembered predicate read: what a transaction was looking FOR, not just what it found. */
    record Pred(String label, Predicate<Map.Entry<String, Integer>> test) {}

    private final Map<String, List<Version>> store = new LinkedHashMap<>();
    private final List<Tx> txns = new ArrayList<>();
    private long clock = 1;
    final List<String> log = new ArrayList<>();

    // 2PL lock tables
    private final Map<String, Set<Long>> sharedLocks = new LinkedHashMap<>();
    private final Map<String, Long> exclusiveLocks = new LinkedHashMap<>();
    private final Set<Long> tableSharedLocks = new LinkedHashSet<>();

    // ------------------------------------------------------------------ setup

    /** Seed data, committed before any transaction in the scenario starts. */
    void seed(String key, int value) {
        store.computeIfAbsent(key, k -> new ArrayList<>())
             .add(new Version(value, false, clock, Long.MAX_VALUE));
        clock++;
    }

    // ------------------------------------------------------------------ transactions

    Tx begin(Level level) {
        Tx t = new Tx(txns.size() + 1, level, clock++);
        txns.add(t);
        log.add(t.name() + " begins at snapshot " + t.startTs);
        return t;
    }

    /** The moment a read should look at: frozen for a snapshot, always-now for read committed. */
    private long readTs(Tx t) {
        return t.level == Level.READ_COMMITTED ? clock : t.startTs;
    }

    /**
     * The only read that looks inside somebody else's unfinished work. This is the
     * whole of READ UNCOMMITTED, and the reason it is not a level anyone should ask
     * for: the number you get may be taken back a millisecond later.
     */
    private Integer peekUncommitted(Tx self, String key) {
        for (Tx u : txns)
            if (u != self && u.status == Status.ACTIVE && u.writes.containsKey(key)) return u.writes.get(key);
        return versionAt(key, clock);
    }

    private Integer versionAt(String key, long ts) {
        for (Version v : store.getOrDefault(key, List.of()))
            if (v.begin <= ts && ts < v.end) return v.deleted ? null : v.value;
        return null;
    }

    // ------------------------------------------------------------------ reads

    Integer get(Tx t, String key) {
        check(t);
        if (t.level == Level.SERIALIZABLE_2PL) lockShared(t, key);
        // Remembering WHAT WAS READ is the entire difference between snapshot
        // isolation and serializable — nothing else in this class changes.
        if (t.level == Level.SERIALIZABLE_SSI) t.readKeys.add(key);
        if (t.writes.containsKey(key)) return t.writes.get(key);     // your own writes are always visible to you
        Integer v = t.level == Level.READ_UNCOMMITTED ? peekUncommitted(t, key) : versionAt(key, readTs(t));
        log.add(t.name() + " reads " + key + " = " + v + "  (as of " + readTs(t) + ")");
        return v;
    }

    /** A read that asks a QUESTION rather than naming a row: "which doctors are on call?" */
    List<Map.Entry<String, Integer>> scan(Tx t, String label, Predicate<Map.Entry<String, Integer>> p) {
        check(t);
        if (t.level == Level.SERIALIZABLE_2PL) lockTableShared(t);
        if (t.level == Level.SERIALIZABLE_SSI) t.readPreds.add(new Pred(label, p));
        long ts = readTs(t);
        List<Map.Entry<String, Integer>> out = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>(store.keySet());
        keys.addAll(t.writes.keySet());
        for (String k : keys) {
            Integer v = t.writes.containsKey(k) ? t.writes.get(k)
                      : t.level == Level.READ_UNCOMMITTED ? peekUncommitted(t, k) : versionAt(k, ts);
            if (v == null) continue;
            Map.Entry<String, Integer> e = Map.entry(k, v);
            if (p.test(e)) out.add(e);
        }
        log.add(t.name() + " scans [" + label + "] -> " + out.size() + " rows  (as of " + ts + ")");
        return out;
    }

    // ------------------------------------------------------------------ writes

    void put(Tx t, String key, int value) { write(t, key, value); }
    void delete(Tx t, String key) { write(t, key, null); }

    private void write(Tx t, String key, Integer value) {
        check(t);
        if (t.level == Level.SERIALIZABLE_2PL) lockExclusive(t, key);
        // Every level above READ UNCOMMITTED refuses to let you scribble over an
        // uncommitted change. READ UNCOMMITTED takes no locks at all, which is why
        // it allows the one anomaly every standard says must always be prevented.
        else if (t.level != Level.READ_UNCOMMITTED) blockIfSomeoneElseIsWriting(t, key);

        if (t.level == Level.SERIALIZABLE_SSI) noteReadWriteConflicts(t, key, value);

        t.writes.put(key, value);
        log.add(t.name() + " writes " + key + " = " + value + "  (not visible to anyone else yet)");
    }

    /**
     * SSI's whole idea. When T writes a row, every CONCURRENT transaction that
     * already read that row — or asked a question this row answers — now has a
     * stale read. Record an arrow from the reader to the writer.
     *
     * A transaction with an arrow coming IN and an arrow going OUT is sitting in
     * the middle of a cycle that snapshot isolation cannot see. That shape is
     * what gets refused at commit.
     */
    private void noteReadWriteConflicts(Tx t, String key, Integer value) {
        for (Tx u : txns) {
            if (u == t || !concurrent(u, t)) continue;
            boolean readIt = u.readKeys.contains(key);
            if (!readIt) {
                Map.Entry<String, Integer> after = value == null ? null : Map.entry(key, value);
                Integer before = versionAt(key, u.startTs);
                for (Pred p : u.readPreds) {
                    // the row matters to U if it matched U's question before, or matches it now
                    if ((before != null && p.test().test(Map.entry(key, before)))
                        || (after != null && p.test().test(after))) { readIt = true; break; }
                }
            }
            if (readIt) {
                u.rwOut.add(t.id); t.rwIn.add(u.id);
                log.add("  ! " + u.name() + " had read " + key + ", " + t.name() + " is writing it"
                        + "  ->  arrow " + u.name() + " -> " + t.name());
            }
        }
    }

    /**
     * A "pivot" is a transaction with an arrow coming in AND an arrow going out.
     * Only that shape can close a cycle, so only that transaction has to go.
     *
     * Arrows from transactions that have already been thrown out do not count —
     * which is why the SECOND transaction in a write-skew pair gets to commit
     * once the first has been refused. Refusing both would be safe and wasteful.
     */
    private boolean isPivot(Tx t) {
        boolean in = t.rwIn.stream().anyMatch(id -> byId(id).status != Status.ABORTED);
        boolean out = t.rwOut.stream().anyMatch(id -> byId(id).status != Status.ABORTED);
        return in && out;
    }

    private Tx byId(long id) { return txns.get((int) id - 1); }

    private boolean concurrent(Tx a, Tx b) {
        boolean aFinishedFirst = a.commitTs != 0 && a.commitTs < b.startTs;
        boolean bFinishedFirst = b.commitTs != 0 && b.commitTs < a.startTs;
        return !aFinishedFirst && !bFinishedFirst;
    }

    // ------------------------------------------------------------------ commit

    void commit(Tx t) {
        check(t);
        if (t.level == Level.SNAPSHOT || t.level == Level.SERIALIZABLE_SSI) {
            // FIRST COMMITTER WINS: if any key I wrote gained a new version after
            // my snapshot was taken, someone beat me to it and I must not proceed.
            for (String key : t.writes.keySet())
                for (Version v : store.getOrDefault(key, List.of()))
                    if (v.begin > t.startTs) {
                        abort(t);
                        log.add(t.name() + " is refused: someone else committed a change to " + key + " first");
                        throw new Abort("40001", "write conflict on " + key
                            + ": another transaction committed a change to it after " + t.name() + " started");
                    }
        }
        if (t.level == Level.SERIALIZABLE_SSI && isPivot(t)) {
            abort(t);
            log.add(t.name() + " is refused: it has an arrow in AND an arrow out — it is the pivot of a cycle");
            throw new Abort("40001", t.name() + " both read something another transaction overwrote, and "
                + "overwrote something another transaction read — no serial order can explain that");
        }
        long ts = clock++;
        for (var e : t.writes.entrySet()) {
            List<Version> chain = store.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
            for (int i = 0; i < chain.size(); i++)
                if (chain.get(i).end == Long.MAX_VALUE)
                    chain.set(i, new Version(chain.get(i).value, chain.get(i).deleted, chain.get(i).begin, ts));
            chain.add(new Version(e.getValue() == null ? 0 : e.getValue(), e.getValue() == null, ts, Long.MAX_VALUE));
        }
        t.commitTs = ts; t.status = Status.COMMITTED;
        releaseLocks(t);
        log.add(t.name() + " commits at " + ts + "  (its writes become visible now)");
    }

    void rollback(Tx t) {
        if (t.status != Status.ACTIVE) return;
        abort(t);
        log.add(t.name() + " rolls back  (its writes are discarded)");
    }

    /** The engine picking a deadlock victim, and saying so. */
    void kill(Tx t, String why) {
        abort(t);
        log.add(t.name() + " is thrown out: " + why);
    }

    void note(String line) { log.add(line); }

    private void abort(Tx t) { t.writes.clear(); t.status = Status.ABORTED; releaseLocks(t); }

    private void check(Tx t) {
        if (t.status != Status.ACTIVE) throw new Abort("25000", t.name() + " is no longer running");
    }

    // ------------------------------------------------------------------ locks (2PL) and dirty-write protection

    /** No level lets you scribble over a change someone else has not committed yet. */
    private void blockIfSomeoneElseIsWriting(Tx t, String key) {
        for (Tx u : txns)
            if (u != t && u.status == Status.ACTIVE && u.writes.containsKey(key))
                throw new Blocked(u.name() + " has an uncommitted change to " + key);
    }

    private void lockShared(Tx t, String key) {
        Long x = exclusiveLocks.get(key);
        if (x != null && x != t.id) throw new Blocked("T" + x + " holds a write lock on " + key);
        sharedLocks.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(t.id);
        t.shared.add(key);
    }

    private void lockExclusive(Tx t, String key) {
        Long x = exclusiveLocks.get(key);
        if (x != null && x != t.id) throw new Blocked("T" + x + " holds a write lock on " + key);
        for (Long s : sharedLocks.getOrDefault(key, Set.of()))
            if (s != t.id) throw new Blocked("T" + s + " holds a read lock on " + key);
        // A predicate read locked the whole table, so a write anywhere must wait.
        // Coarse — a real engine locks index ranges — but it is the same idea and
        // it is what stops a row appearing inside someone else's question.
        for (Long s : tableSharedLocks) if (s != t.id) throw new Blocked("T" + s + " has a read lock on the whole table");
        exclusiveLocks.put(key, t.id);
        t.exclusive.add(key);
    }

    private void lockTableShared(Tx t) {
        for (var e : exclusiveLocks.entrySet())
            if (e.getValue() != t.id) throw new Blocked("T" + e.getValue() + " holds a write lock on " + e.getKey());
        tableSharedLocks.add(t.id); t.tableShared = true;
    }

    private void releaseLocks(Tx t) {
        for (String k : t.shared) sharedLocks.getOrDefault(k, new LinkedHashSet<>()).remove(t.id);
        for (String k : t.exclusive) exclusiveLocks.remove(k, t.id);
        tableSharedLocks.remove(t.id);
        t.shared.clear(); t.exclusive.clear(); t.tableShared = false;
    }

    // ------------------------------------------------------------------ inspection

    /** What a reader starting right now would see. */
    Integer committed(String key) { return versionAt(key, clock); }

    int countCommitted(Predicate<Map.Entry<String, Integer>> p) {
        int n = 0;
        for (String k : store.keySet()) {
            Integer v = committed(k);
            if (v != null && p.test(Map.entry(k, v))) n++;
        }
        return n;
    }

    /** The version chain for one key — the picture of MVCC. */
    String chain(String key) {
        StringBuilder sb = new StringBuilder(key + ": ");
        for (Version v : store.getOrDefault(key, List.of()))
            sb.append("[").append(v.deleted ? "deleted" : v.value).append(" from ").append(v.begin)
              .append(v.end == Long.MAX_VALUE ? " onwards" : " to " + v.end).append("] ");
        return sb.toString().trim();
    }
}
