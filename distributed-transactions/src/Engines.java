// Engines.java — six ways to run one transaction across several shards.
//
//   NAIVE            commit at each shard independently (the dual write)
//   TPC              2PC for atomic durability; readers read latest committed, no locks
//   TPC_2PL          2PC + distributed strict two-phase locking (readers lock too)
//   PERCO            snapshot reads + 2PC whose commit point is a data row (Percolator),
//                    timestamps from a central oracle
//   PERCO_CLOCK      same, but timestamps from each client's (skewed) clock
//   PERCO_CLOCK_WAIT same, plus TrueTime-style commit wait
//
// Every transaction is a step-driven state machine so the scheduler can
// interleave transactions, readers, and crashes between ANY two actions.
import java.util.*;

public class Engines {
  public enum Engine { NAIVE, TPC, TPC_2PL, PERCO, PERCO_CLOCK, PERCO_CLOCK_WAIT }

  public static final long WAIT_TIMEOUT = 25; // ticks a txn waits for a lock before giving up
  public static final long LOCK_TTL     = 30; // ticks before a reader may treat a percolator lock as abandoned

  // ---------- shared environment: cluster + counters the drivers report into ----------
  public static class Env {
    public final Core.Cluster cluster;
    public final Engine engine;
    public long total;                 // expected sum of all balances (the invariant)
    public long maxAckedCommitTs = 0;  // largest commitTs whose client has been told "done"

    public long commits, timeoutAborts, conflictAborts, rolledBackByReader;
    public long fractured, staleSnapshots, blockedForever, readerRestarts;
    public long readerWaitTicks, commitLatencyTicks, commitWaitTicks;
    public long rollForwards, rollBacks; // resolution work done by READERS, not the coordinator

    public Env(Core.Cluster cluster, Engine engine) { this.cluster = cluster; this.engine = engine; }
    public Core.Bugs bugs() { return cluster.bugs; }
  }

  // ---------- timestamp sources ----------
  public interface TsSource {
    long startTs(long tick);
    long commitTs(long tick, long startTs);
    default boolean commitWaitDone(long tick, long commitTs) { return true; }
  }
  /** The oracle: a single counter. Trivially correct ordering; a single point of scale/failure. */
  public static class OracleTs implements TsSource {
    final Core.Oracle o;
    public OracleTs(Core.Oracle o) { this.o = o; }
    public long startTs(long tick) { return o.next(); }
    public long commitTs(long tick, long startTs) { return o.next(); }
  }
  /**
   * A client clock. offset is how wrong this client's clock is (ticks, may be negative).
   * With trueTimeWait=false this is "just trust NTP": ts = local now.
   * With trueTimeWait=true this is TrueTime: every timestamp is now().latest (= real+offset+eps,
   * guaranteed >= real time since |offset| <= eps), and commit waits until now().earliest has
   * passed commitTs — so by the time the client is told "done", commitTs is in the PAST for
   * every clock in the system.
   */
  public static class ClockTs implements TsSource {
    final long offset, eps; final boolean trueTimeWait; final int clientId;
    public ClockTs(long offset, long eps, boolean trueTimeWait, int clientId) {
      this.offset = offset; this.eps = eps; this.trueTimeWait = trueTimeWait; this.clientId = clientId;
    }
    // ts space: tick*1000 + clientId keeps every timestamp unique and ordered by (time, client)
    private long now(long tick) { return (tick + offset + (trueTimeWait ? eps : 0)) * 1000 + clientId; }
    public long startTs(long tick) { return now(tick); }
    public long commitTs(long tick, long startTs) { return Math.max(now(tick), startTs + 1); }
    public boolean commitWaitDone(long tick, long commitTs) {
      if (!trueTimeWait) return true;
      return (tick + offset - eps) * 1000 > commitTs;   // now().earliest > commitTs
    }
  }

  // ---------- base class: kill support + latency accounting ----------
  public static abstract class Txn implements Core.Driver {
    public final String id; public final Env env;
    public boolean finished, aborted, killed;
    public int killAtStep = -1;      // -1 = never; otherwise the client dies before taking step N
    public int stepsTaken = 0;
    public long firstTick = -1, ackTick = -1;
    protected long waitedSince = -1;
    protected Txn(String id, Env env) { this.id = id; this.env = env; }
    public String id() { return id; }
    public boolean done() { return finished; }
    public final void step(long tick) {
      if (finished) return;
      if (firstTick < 0) firstTick = tick;
      if (killAtStep >= 0 && stepsTaken >= killAtStep) { killed = true; finished = true; return; }
      stepsTaken++;
      doStep(tick);
    }
    protected abstract void doStep(long tick);
    /** true = timed out (caller must abort); false = keep waiting */
    protected boolean waitOrTimeout(long tick) {
      if (waitedSince < 0) { waitedSince = tick; return false; }
      if (tick - waitedSince > WAIT_TIMEOUT) { waitedSince = -1; return true; }
      return false;
    }
    protected void ack(long tick) {
      finished = true; ackTick = tick;
      env.commits++; env.commitLatencyTicks += (ackTick - firstTick);
    }
  }

  // =====================================================================
  // NAIVE: apply-and-commit at each shard, one after the other. A crash
  // between the two steps destroys or invents money, permanently.
  // =====================================================================
  public static class NaiveTransfer extends Txn {
    final String[] keys; final long[] deltas; int i = 0;
    public NaiveTransfer(String id, Env env, String from, String to, long amt) {
      super(id, env); keys = new String[]{from, to}; deltas = new long[]{-amt, +amt};
    }
    protected void doStep(long tick) {
      Core.Shard s = env.cluster.of(keys[i]);
      s.put(keys[i], ++s.localSeq, s.latest(keys[i]) + deltas[i]);   // local commit, right now
      if (++i == keys.length) ack(tick);
    }
  }

  // =====================================================================
  // TPC family: prepare (write-lock + stage) on every shard, log the
  // decision, then commit on every shard. Atomic durability — a killed
  // transaction can always be finished or undone by recovery (see the
  // two-phase-commit lab). What it does NOT provide is atomic VISIBILITY:
  // between the per-shard commit steps, the transaction is half-visible.
  // =====================================================================
  public static class TpcTransfer extends Txn {
    final String[] keys; final long[] deltas; final boolean readersLock;
    public boolean decisionLogged = false;
    final List<String> lockedKeys = new ArrayList<>();
    int state = 0; // 0..k-1 = LOCK(i), k = DECIDE, k+1..2k = COMMIT(i)
    public TpcTransfer(String id, Env env, String from, String to, long amt, boolean readersLock, boolean orderedLocks) {
      super(id, env);
      String[] ks = {from, to}; long[] ds = {-amt, +amt};
      if (orderedLocks && from.compareTo(to) > 0) {   // deadlock fix from isolation-levels: lock in a fixed order
        ks = new String[]{to, from}; ds = new long[]{+amt, -amt};
      }
      keys = ks; deltas = ds; this.readersLock = readersLock;
    }
    protected void doStep(long tick) {
      int k = keys.length;
      if (state < k) {                                   // PREPARE key[state]
        String key = keys[state]; Core.Shard s = env.cluster.of(key);
        String holder = s.writeLock.get(key);
        boolean conflict = (holder != null && !holder.equals(id))
                        || (readersLock && s.readLockedByOthers(key, id));
        if (conflict) { if (waitOrTimeout(tick)) abortSelf(); return; }
        s.writeLock.put(key, id);
        s.staged2pc.put(key, s.latest(key) + deltas[state]);
        lockedKeys.add(key); waitedSince = -1; state++;
      } else if (state == k) {                           // DECIDE (durably logged commit decision)
        decisionLogged = true; state++;
      } else {                                           // COMMIT key[state-k-1]
        int i = state - k - 1;
        commitKey(i);
        if (++state == 2 * k + 1) ack(tick);
      }
    }
    private void commitKey(int i) {
      String key = keys[i]; Core.Shard s = env.cluster.of(key);
      Long v = s.staged2pc.remove(key);
      if (v != null) s.put(key, ++s.localSeq, v);
      s.writeLock.remove(key);
    }
    void abortSelf() {
      for (String key : lockedKeys) {
        Core.Shard s = env.cluster.of(key);
        if (id.equals(s.writeLock.get(key))) { s.writeLock.remove(key); s.staged2pc.remove(key); }
      }
      aborted = true; finished = true; env.timeoutAborts++;
    }
    /** What the coordinator's recovery would do after a crash: finish if decided, undo otherwise. */
    public void recover() {
      if (!killed) return;
      if (decisionLogged) { for (int i = 0; i < keys.length; i++) commitKey(i); env.commits++; }
      else abortSelfQuiet();
    }
    private void abortSelfQuiet() {
      for (String key : lockedKeys) {
        Core.Shard s = env.cluster.of(key);
        if (id.equals(s.writeLock.get(key))) { s.writeLock.remove(key); s.staged2pc.remove(key); }
      }
    }
  }

  // =====================================================================
  // PERCO family: snapshot reads at startTs; prewrite (lock + stage) with
  // the PRIMARY key first; then ONE local write at the primary — replacing
  // the primary lock with a commit record — is the commit point for the
  // whole transaction. Secondaries commit lazily; any reader can finish
  // the job by consulting the primary. Coordinator death never blocks.
  // =====================================================================
  public static class PercoTransfer extends Txn {
    final String[] keys; final long[] deltas; final TsSource ts;
    public long startTs = -1, commitTs = -1;
    final List<String> prewritten = new ArrayList<>();
    int state = 0;
    long waitStartedCommitWait = -1;
    public PercoTransfer(String id, Env env, TsSource ts, String from, String to, long amt) {
      super(id, env); this.ts = ts;
      keys = new String[]{from, to}; deltas = new long[]{-amt, +amt};
    }
    String primary() { return keys[0]; }

    protected void doStep(long tick) {
      int k = keys.length;
      if (state == 0) {                                       // START: get the snapshot timestamp
        startTs = ts.startTs(tick); state++;
      } else if (state <= k) {                                // PREWRITE key[state-1] (primary first)
        String key = keys[state - 1]; Core.Shard s = env.cluster.of(key);
        Core.PercoLock ex = s.locks.get(key);
        if (ex != null && !ex.txnId.equals(id)) { if (waitOrTimeout(tick)) abortSelf(false); return; }
        if (s.latestCommitTs(key) > startTs) { abortSelf(true); return; }  // first-committer-wins
        long staged = s.floorValue(key, startTs) + deltas[state - 1];
        s.locks.put(key, new Core.PercoLock(startTs, id, primary(), tick));
        s.stagedPerco.put(key, staged);
        prewritten.add(key); waitedSince = -1; state++;
      } else if (state == k + 1) {                            // GET COMMIT TS
        commitTs = ts.commitTs(tick, startTs); state++;
      } else if (state == k + 2) {                            // COMMIT WAIT (TrueTime only)
        if (!ts.commitWaitDone(tick, commitTs)) { env.commitWaitTicks++; return; }
        state++;
      } else {                                                // COMMIT steps
        int idx = state - (k + 3);                            // 0 = first commit step
        boolean secondariesFirst = env.bugs().commitSecondariesFirst;
        // normal order: primary (idx 0), then secondaries (idx 1..k-1)
        // bugged order: secondaries (idx 0..k-2), then primary (idx k-1)
        int keyIdx = secondariesFirst ? (idx == k - 1 ? 0 : idx + 1) : idx;
        if (keyIdx == 0) { if (!commitPrimary()) return; }
        else commitSecondary(keys[keyIdx]);
        if (++state == 2 * k + 3) ack(tick);
      }
    }
    /** THE commit point: one shard-local write. Returns false if we discover a reader rolled us back. */
    private boolean commitPrimary() {
      Core.Shard p = env.cluster.of(primary());
      Core.PercoLock l = p.locks.get(primary());
      if (l == null || l.startTs != startTs) {                // a reader decided we were dead and cleaned us up
        abortSelf(false); env.timeoutAborts--; env.rolledBackByReader++;
        return false;
      }
      long v = p.stagedPerco.remove(primary());
      p.locks.remove(primary());
      p.put(primary(), commitTs, v);
      p.commitRecord.put(startTs, commitTs);                  // <-- the whole transaction commits HERE
      return true;
    }
    private void commitSecondary(String key) {
      Core.Shard s = env.cluster.of(key);
      Core.PercoLock l = s.locks.get(key);
      if (l != null && l.startTs == startTs) {                // a reader may already have rolled it forward
        s.put(key, commitTs, s.stagedPerco.remove(key));
        s.locks.remove(key);
      }
    }
    void abortSelf(boolean conflict) {
      for (String key : prewritten) {
        Core.Shard s = env.cluster.of(key);
        Core.PercoLock l = s.locks.get(key);
        if (l != null && l.startTs == startTs && l.txnId.equals(id)) { s.locks.remove(key); s.stagedPerco.remove(key); }
      }
      aborted = true; finished = true;
      if (conflict) env.conflictAborts++; else env.timeoutAborts++;
    }
    protected void ack(long tick) {
      super.ack(tick);
      env.maxAckedCommitTs = Math.max(env.maxAckedCommitTs, commitTs);
    }
  }

  // =====================================================================
  // Readers
  // =====================================================================

  /** NAIVE/TPC reader: latest committed value per key, no locks, no snapshot. */
  public static class LatestReader extends Txn {
    final List<String> keys; long sum = 0; int i = 0;
    public final Map<String, Long> seen = new LinkedHashMap<>();
    final boolean allKeys;
    public LatestReader(String id, Env env, List<String> keys, boolean allKeys) {
      super(id, env); this.keys = keys; this.allKeys = allKeys;
    }
    protected void doStep(long tick) {
      String key = keys.get(i);
      long v = env.cluster.of(key).latest(key);
      seen.put(key, v); sum += v;
      if (++i == keys.size()) {
        if (allKeys && sum != env.total) env.fractured++;
        finished = true;
      }
    }
  }

  /** TPC_2PL reader: takes a read lock per key, holds all locks to the end (strict 2PL). */
  public static class LockingReader extends Txn {
    final List<String> keys; long sum = 0; int i = 0; int attempts = 0;
    public final Map<String, Long> seen = new LinkedHashMap<>();
    final List<String> held = new ArrayList<>();
    final boolean allKeys;
    public boolean gaveUp = false;
    public LockingReader(String id, Env env, List<String> keys, boolean allKeys) {
      super(id, env); this.keys = keys; this.allKeys = allKeys;
    }
    protected void doStep(long tick) {
      String key = keys.get(i); Core.Shard s = env.cluster.of(key);
      String w = s.writeLock.get(key);
      if (w != null) {                                   // writer holds it: wait
        env.readerWaitTicks++;
        if (waitOrTimeout(tick)) restart();
        return;
      }
      s.addReadLock(key, id); held.add(key);
      long v = s.latest(key); seen.put(key, v); sum += v;
      if (env.bugs().earlyReadLockRelease) { s.removeReadLock(key, id); held.remove(key); }
      waitedSince = -1;
      if (++i == keys.size()) {
        for (String h : held) env.cluster.of(h).removeReadLock(h, id);
        if (allKeys && sum != env.total) env.fractured++;
        finished = true;
      }
    }
    private void restart() {
      for (String h : held) env.cluster.of(h).removeReadLock(h, id);
      held.clear(); seen.clear(); sum = 0; i = 0;
      if (++attempts > 3) { gaveUp = true; finished = true; env.blockedForever++; }
      else env.readerRestarts++;
    }
  }

  /** PERCO-family reader: one snapshot timestamp for ALL shards; resolves in-flight locks itself. */
  public static class SnapshotReader extends Txn {
    final List<String> keys; long sum = 0; int i = 0;
    public long snapTs = -1;
    public final Map<String, Long> seen = new LinkedHashMap<>();
    final TsSource ts; final boolean allKeys;
    public SnapshotReader(String id, Env env, TsSource ts, List<String> keys, boolean allKeys) {
      super(id, env); this.ts = ts; this.keys = keys; this.allKeys = allKeys;
    }
    protected void doStep(long tick) {
      if (snapTs < 0) {
        snapTs = ts.startTs(tick);
        // real-time (external consistency) check: a transaction whose client was already told
        // "done" has commitTs <= maxAckedCommitTs; a snapshot below that will silently miss it.
        if (snapTs < env.maxAckedCommitTs) env.staleSnapshots++;
        return;
      }
      String key = keys.get(i);
      Long v = resolveAndRead(env, key, snapTs, tick);
      if (v == null) { env.readerWaitTicks++; return; }   // in-flight lock, within TTL: retry next step
      seen.put(key, v); sum += v;
      if (++i == keys.size()) {
        if (allKeys && sum != env.total) env.fractured++;
        finished = true;
      }
    }
  }

  /**
   * The percolator read rule — the heart of the whole design. Given a key and a snapshot,
   * either return the value visible at snapTs, or return null meaning "someone is mid-commit,
   * ask again shortly". Any reader can FINISH a dead client's commit (roll forward) or undo a
   * dead client's mess (roll back), because the truth lives in the primary's commit record,
   * not in the coordinator's memory.
   */
  public static Long resolveAndRead(Env env, String key, long snapTs, long tick) {
    Core.Shard s = env.cluster.of(key);
    Core.PercoLock l = s.locks.get(key);
    if (l == null || l.startTs > snapTs || env.bugs().readerIgnoresLocks)
      return s.floorValue(key, snapTs);                  // no relevant claim: plain snapshot read

    if (env.bugs().resolveWithoutPrimaryCheck) {         // BUG: assume any lock we meet is garbage
      s.locks.remove(key); s.stagedPerco.remove(key);
      env.rollBacks++;
      return s.floorValue(key, snapTs);
    }

    Core.Shard p = env.cluster.of(l.primaryKey);
    Long cts = p.commitRecord.get(l.startTs);
    if (cts != null) {                                   // the txn DID commit (primary flipped)
      if (cts <= snapTs) {                               // ...before our snapshot: roll it forward here
        Long staged = s.stagedPerco.remove(key);
        if (staged != null) s.put(key, cts, staged);
        s.locks.remove(key);
        env.rollForwards++;
      }                                                  // ...after our snapshot: not ours to see
      return s.floorValue(key, snapTs);
    }
    Core.PercoLock pl = p.locks.get(l.primaryKey);
    if (pl != null && pl.startTs == l.startTs) {         // still in flight
      if (tick - l.createdTick > LOCK_TTL) {             // ...but abandoned: kill it, primary first
        p.locks.remove(l.primaryKey); p.stagedPerco.remove(l.primaryKey);
        if (s != p || !key.equals(l.primaryKey)) { s.locks.remove(key); s.stagedPerco.remove(key); }
        env.rollBacks++;
        return s.floorValue(key, snapTs);
      }
      return null;                                       // give it a moment
    }
    // primary lock gone AND no commit record => the txn aborted; clean this leftover
    s.locks.remove(key); s.stagedPerco.remove(key);
    env.rollBacks++;
    return s.floorValue(key, snapTs);
  }

  /** End-of-run sweep: what lazy resolution would eventually do to every leftover lock. */
  public static void resolveEverything(Env env) {
    for (Core.Shard s : env.cluster.shards) {
      for (String key : new ArrayList<>(s.locks.keySet())) {
        resolveAndRead(env, key, Long.MAX_VALUE, Long.MAX_VALUE / 2);
      }
    }
  }

  // =====================================================================
  // Read-modify-write transactions (for the write-skew scenario)
  // =====================================================================

  /** Percolator RMW: snapshot-read some keys, decide, write absolute values. */
  public static class PercoRmw extends Txn {
    final List<String> readKeys; final TsSource ts;
    final java.util.function.Function<Map<String, Long>, Map<String, Long>> decide;
    final Map<String, Long> reads = new LinkedHashMap<>();
    List<String> writeKeys; Map<String, Long> writes;
    long startTs = -1, commitTs = -1; int phase = 0; int i = 0;
    final List<String> prewritten = new ArrayList<>();
    public PercoRmw(String id, Env env, TsSource ts, List<String> readKeys,
                    java.util.function.Function<Map<String, Long>, Map<String, Long>> decide) {
      super(id, env); this.ts = ts; this.readKeys = readKeys; this.decide = decide;
    }
    String primary() { return writeKeys.get(0); }
    protected void doStep(long tick) {
      if (phase == 0) { startTs = ts.startTs(tick); phase = 1; return; }
      if (phase == 1) {                                       // READ under the snapshot
        Long v = resolveAndRead(env, readKeys.get(i), startTs, tick);
        if (v == null) return;
        reads.put(readKeys.get(i), v);
        if (++i == readKeys.size()) {
          writes = decide.apply(reads);
          if (writes.isEmpty()) { finished = true; return; }
          writeKeys = new ArrayList<>(writes.keySet()); i = 0; phase = 2;
        }
        return;
      }
      if (phase == 2) {                                       // PREWRITE (primary first)
        String key = writeKeys.get(i); Core.Shard s = env.cluster.of(key);
        Core.PercoLock ex = s.locks.get(key);
        if (ex != null && !ex.txnId.equals(id)) { if (waitOrTimeout(tick)) abortSelf(); return; }
        if (s.latestCommitTs(key) > startTs) { abortSelf(); env.timeoutAborts--; env.conflictAborts++; return; }
        s.locks.put(key, new Core.PercoLock(startTs, id, primary(), tick));
        s.stagedPerco.put(key, writes.get(key));
        prewritten.add(key); waitedSince = -1;
        if (++i == writeKeys.size()) { phase = 3; }
        return;
      }
      if (phase == 3) { commitTs = ts.commitTs(tick, startTs); i = 0; phase = 4; return; }
      // COMMIT: primary first, then secondaries
      String key = writeKeys.get(i); Core.Shard s = env.cluster.of(key);
      Core.PercoLock l = s.locks.get(key);
      if (i == 0) {
        if (l == null || l.startTs != startTs) { abortSelf(); env.timeoutAborts--; env.rolledBackByReader++; return; }
        s.put(key, commitTs, s.stagedPerco.remove(key)); s.locks.remove(key);
        s.commitRecord.put(startTs, commitTs);
      } else if (l != null && l.startTs == startTs) {
        s.put(key, commitTs, s.stagedPerco.remove(key)); s.locks.remove(key);
      }
      if (++i == writeKeys.size()) ack(tick);
    }
    void abortSelf() {
      for (String key : prewritten) {
        Core.Shard s = env.cluster.of(key);
        Core.PercoLock l = s.locks.get(key);
        if (l != null && l.txnId.equals(id)) { s.locks.remove(key); s.stagedPerco.remove(key); }
      }
      aborted = true; finished = true; env.timeoutAborts++;
    }
  }

  /** Distributed strict-2PL RMW: read locks on the read set, write locks on the write set, all held to the end. */
  public static class TplRmw extends Txn {
    final List<String> readKeys;
    final java.util.function.Function<Map<String, Long>, Map<String, Long>> decide;
    final Map<String, Long> reads = new LinkedHashMap<>();
    List<String> writeKeys; Map<String, Long> writes;
    int phase = 0; int i = 0;
    final List<String> readHeld = new ArrayList<>(), writeHeld = new ArrayList<>();
    public TplRmw(String id, Env env, List<String> readKeys,
                  java.util.function.Function<Map<String, Long>, Map<String, Long>> decide) {
      super(id, env); this.readKeys = readKeys; this.decide = decide;
    }
    protected void doStep(long tick) {
      if (phase == 0) {                                       // read phase, under read locks
        String key = readKeys.get(i); Core.Shard s = env.cluster.of(key);
        String w = s.writeLock.get(key);
        if (w != null && !w.equals(id)) { if (waitOrTimeout(tick)) abortSelf(); return; }
        s.addReadLock(key, id); if (!readHeld.contains(key)) readHeld.add(key);
        reads.put(key, s.latest(key)); waitedSince = -1;
        if (++i == readKeys.size()) {
          writes = decide.apply(reads);
          if (writes.isEmpty()) { releaseAll(); finished = true; return; }
          writeKeys = new ArrayList<>(writes.keySet()); i = 0; phase = 1;
        }
        return;
      }
      if (phase == 1) {                                       // write phase: upgrade to write locks
        String key = writeKeys.get(i); Core.Shard s = env.cluster.of(key);
        String w = s.writeLock.get(key);
        boolean conflict = (w != null && !w.equals(id)) || s.readLockedByOthers(key, id);
        if (conflict) { if (waitOrTimeout(tick)) abortSelf(); return; }
        s.writeLock.put(key, id); if (!writeHeld.contains(key)) writeHeld.add(key);
        waitedSince = -1;
        if (++i == writeKeys.size()) { i = 0; phase = 2; }
        return;
      }
      // commit phase: install values, then release everything
      String key = writeKeys.get(i); Core.Shard s = env.cluster.of(key);
      s.put(key, ++s.localSeq, writes.get(key));
      if (++i == writeKeys.size()) { releaseAll(); ack(tick); }
    }
    void abortSelf() { releaseAll(); aborted = true; finished = true; env.timeoutAborts++; }
    void releaseAll() {
      for (String k : readHeld) env.cluster.of(k).removeReadLock(k, id);
      for (String k : writeHeld) { Core.Shard s = env.cluster.of(k); if (id.equals(s.writeLock.get(k))) s.writeLock.remove(k); }
    }
  }
}
