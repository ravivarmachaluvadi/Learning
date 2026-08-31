// Tests.java — every claim the README makes, executed.
//   java src/Tests.java          -> the full suite
//   java src/Tests.java broken   -> enable each planted bug and show which guarantees it breaks
import java.util.*;

public class Tests {
  static int pass = 0, fail = 0;
  static List<String> failures = new ArrayList<>();

  static void check(String name, boolean ok) {
    if (ok) { pass++; System.out.println("  ok  " + name); }
    else { fail++; failures.add(name); System.out.println("  FAIL " + name); }
  }

  // tiny cluster: alice on S0, bob on S1, 100 each
  static Object[] twoShardCluster(Engines.Engine e) {
    Core.Cluster cl = new Core.Cluster(2);
    cl.place("alice", 0, 100); cl.place("bob", 1, 100);
    Engines.Env env = new Engines.Env(cl, e); env.total = 200;
    return new Object[]{cl, env};
  }

  static void stepN(Core.Driver d, long fromTick, int n) {
    for (int i = 0; i < n; i++) d.step(fromTick + i);
  }
  static void finish(Engines.Txn t, long fromTick) {
    long tick = fromTick;
    for (int i = 0; i < 200 && !t.done(); i++) t.step(tick++);
  }

  public static void main(String[] args) {
    if (args.length > 0 && args[0].equals("broken")) { brokenSuite(); return; }

    System.out.println("== NAIVE: the dual write, distributed ==");
    naiveCrashLosesMoney();
    naiveAuditorSeesHalfTransfer();

    System.out.println("== TPC: atomic durability, broken visibility ==");
    tpcAtomicAfterCrashWithRecovery();
    tpcKilledBeforeDecisionAborts();
    tpcFracturedReadInCommitWindow();

    System.out.println("== TPC_2PL: correct but blocking ==");
    d2plReaderBlocksNoFracture();
    d2plCrossTransfersDeadlock();
    orderedLocksPreventDeadlock();
    d2plCoordinatorDeathPoisonsReaders();

    System.out.println("== PERCO: the commit point moves into a data row ==");
    percoHappyPath();
    percoReaderMidCommitRollsForward();
    percoClientDiesBeforePrimaryFlipRollsBack();
    percoClientDiesAfterPrimaryFlipRollsForward();
    percoFirstCommitterWins();
    percoWriteSkewAllowed();
    d2plWriteSkewPrevented();
    oracleCallsPerTxn();

    System.out.println("== Clocks: external consistency and commit wait ==");
    clockSkewStaleRead();
    clockCommitWaitFixesIt();

    System.out.println("== Fuzz: 20 random universes per claim ==");
    fuzzChecks();

    System.out.println();
    System.out.println(pass + " passed, " + fail + " failed" + (fail > 0 ? " -> " + failures : ""));
    if (fail > 0) System.exit(1);
  }

  // ---------------- NAIVE ----------------

  static void naiveCrashLosesMoney() {
    Object[] w = twoShardCluster(Engines.Engine.NAIVE);
    Core.Cluster cl = (Core.Cluster) w[0]; Engines.Env env = (Engines.Env) w[1];
    Engines.NaiveTransfer t = new Engines.NaiveTransfer("T1", env, "alice", "bob", 50);
    t.killAtStep = 1;                     // dies between the two local commits
    stepN(t, 0, 3);
    check("naive_crash_between_shards_loses_money_forever",
        t.killed && cl.of("alice").latest("alice") == 50 && cl.of("bob").latest("bob") == 100
        && cl.totalCommitted() == 150);
  }

  static void naiveAuditorSeesHalfTransfer() {
    Object[] w = twoShardCluster(Engines.Engine.NAIVE);
    Engines.Env env = (Engines.Env) w[1];
    Engines.NaiveTransfer t = new Engines.NaiveTransfer("T1", env, "alice", "bob", 50);
    Engines.LatestReader r = new Engines.LatestReader("R1", env, List.of("alice", "bob"), true);
    t.step(0);                            // debit committed on S0
    r.step(1); r.step(2);                 // auditor sweeps both shards NOW
    t.step(3);                            // credit lands after the audit
    check("naive_auditor_sees_money_vanish_mid_transfer", env.fractured == 1 && r.sum == 150);
  }

  // ---------------- TPC ----------------

  static void tpcAtomicAfterCrashWithRecovery() {
    Object[] w = twoShardCluster(Engines.Engine.TPC);
    Core.Cluster cl = (Core.Cluster) w[0]; Engines.Env env = (Engines.Env) w[1];
    Engines.TpcTransfer t = new Engines.TpcTransfer("T1", env, "alice", "bob", 50, false, false);
    t.killAtStep = 3;                     // locks both, logs the decision, dies before either commit
    stepN(t, 0, 4);
    boolean lockedWhileDead = cl.of("alice").writeLock.containsKey("alice");
    t.recover();
    check("tpc_decision_logged_recovery_completes_commit",
        t.killed && lockedWhileDead && cl.of("alice").latest("alice") == 50
        && cl.of("bob").latest("bob") == 150 && cl.totalCommitted() == 200);
  }

  static void tpcKilledBeforeDecisionAborts() {
    Object[] w = twoShardCluster(Engines.Engine.TPC);
    Core.Cluster cl = (Core.Cluster) w[0]; Engines.Env env = (Engines.Env) w[1];
    Engines.TpcTransfer t = new Engines.TpcTransfer("T1", env, "alice", "bob", 50, false, false);
    t.killAtStep = 2;                     // dies after locking both, before the decision
    stepN(t, 0, 3);
    t.recover();
    check("tpc_no_decision_recovery_aborts_cleanly",
        t.killed && !t.decisionLogged && cl.totalCommitted() == 200
        && cl.of("alice").writeLock.isEmpty() && cl.of("bob").writeLock.isEmpty());
  }

  static void tpcFracturedReadInCommitWindow() {
    Object[] w = twoShardCluster(Engines.Engine.TPC);
    Core.Cluster cl = (Core.Cluster) w[0]; Engines.Env env = (Engines.Env) w[1];
    Engines.TpcTransfer t = new Engines.TpcTransfer("T1", env, "alice", "bob", 50, false, false);
    Engines.LatestReader r = new Engines.LatestReader("R1", env, List.of("alice", "bob"), true);
    stepN(t, 0, 4);                       // LOCK, LOCK, DECIDE, COMMIT alice
    r.step(4); r.step(5);                 // audit lands between the two per-shard commits
    t.step(6);                            // COMMIT bob
    check("tpc_commit_is_atomic_but_visibility_is_not",
        env.fractured == 1 && r.sum == 150 && cl.totalCommitted() == 200 && env.commits == 1);
  }

  // ---------------- TPC_2PL ----------------

  static void d2plReaderBlocksNoFracture() {
    Object[] w = twoShardCluster(Engines.Engine.TPC_2PL);
    Engines.Env env = (Engines.Env) w[1];
    Engines.TpcTransfer t = new Engines.TpcTransfer("T1", env, "alice", "bob", 50, true, false);
    Engines.LockingReader r = new Engines.LockingReader("R1", env, List.of("alice", "bob"), true);
    stepN(t, 0, 3);                       // transfer holds both write locks
    r.step(3); r.step(4);                 // reader wants alice: blocked
    long waited = env.readerWaitTicks;
    t.step(5); t.step(6);                 // transfer commits both shards, releases
    stepN(r, 7, 4);                       // reader now sweeps
    check("d2pl_reader_waits_out_the_commit_and_sees_a_whole_transfer",
        waited > 0 && env.fractured == 0 && r.sum == 200);
  }

  static void d2plCrossTransfersDeadlock() {
    Object[] w = twoShardCluster(Engines.Engine.TPC_2PL);
    Core.Cluster cl = (Core.Cluster) w[0]; Engines.Env env = (Engines.Env) w[1];
    Engines.TpcTransfer t1 = new Engines.TpcTransfer("T1", env, "alice", "bob", 10, true, false);
    Engines.TpcTransfer t2 = new Engines.TpcTransfer("T2", env, "bob", "alice", 20, true, false);
    t1.step(0); t2.step(1);               // T1 locks alice, T2 locks bob
    long tick = 2;
    for (int i = 0; i < 40 && !(t1.done() && t2.done()); i++) { t1.step(tick++); t2.step(tick++); }
    // neither shard can see the cycle (each holds half of it); a timeout kills ONE victim,
    // whose released locks let the survivor commit
    check("d2pl_cross_shard_deadlock_timeout_kills_one_victim_survivor_commits",
        t1.aborted && !t2.aborted && env.timeoutAborts == 1 && env.commits == 1
        && cl.totalCommitted() == 200 && cl.of("alice").writeLock.isEmpty());
  }

  static void orderedLocksPreventDeadlock() {
    Object[] w = twoShardCluster(Engines.Engine.TPC_2PL);
    Core.Cluster cl = (Core.Cluster) w[0]; Engines.Env env = (Engines.Env) w[1];
    Engines.TpcTransfer t1 = new Engines.TpcTransfer("T1", env, "alice", "bob", 10, true, true);
    Engines.TpcTransfer t2 = new Engines.TpcTransfer("T2", env, "bob", "alice", 20, true, true);
    long tick = 0;
    for (int i = 0; i < 80 && !(t1.done() && t2.done()); i++) { t1.step(tick++); t2.step(tick++); }
    check("lock_ordering_fix_from_isolation_levels_still_works_across_shards",
        env.timeoutAborts == 0 && env.commits == 2 && cl.of("alice").latest("alice") == 110
        && cl.of("bob").latest("bob") == 90);
  }

  static void d2plCoordinatorDeathPoisonsReaders() {
    Object[] w = twoShardCluster(Engines.Engine.TPC_2PL);
    Engines.Env env = (Engines.Env) w[1];
    Engines.TpcTransfer t = new Engines.TpcTransfer("T1", env, "alice", "bob", 50, true, false);
    t.killAtStep = 3;                     // dies holding both write locks
    stepN(t, 0, 4);
    Engines.LockingReader r = new Engines.LockingReader("R1", env, List.of("alice", "bob"), true);
    long tick = 10;
    for (int i = 0; i < 300 && !r.done(); i++) r.step(tick++);
    check("d2pl_dead_coordinator_blocks_innocent_readers",
        r.gaveUp && env.blockedForever == 1);
  }

  // ---------------- PERCO ----------------

  static void percoHappyPath() {
    Object[] w = twoShardCluster(Engines.Engine.PERCO);
    Core.Cluster cl = (Core.Cluster) w[0]; Engines.Env env = (Engines.Env) w[1];
    Engines.TsSource ts = new Engines.OracleTs(cl.oracle);
    Engines.PercoTransfer t = new Engines.PercoTransfer("T1", env, ts, "alice", "bob", 50);
    finish(t, 0);
    Engines.SnapshotReader r = new Engines.SnapshotReader("R1", env, ts, List.of("alice", "bob"), true);
    stepN(r, 20, 3);
    check("perco_happy_path_commit_and_consistent_snapshot",
        env.commits == 1 && r.sum == 200 && r.seen.get("alice") == 50 && r.seen.get("bob") == 150
        && cl.of("alice").commitRecord.containsKey(t.startTs));
  }

  static void percoReaderMidCommitRollsForward() {
    Object[] w = twoShardCluster(Engines.Engine.PERCO);
    Core.Cluster cl = (Core.Cluster) w[0]; Engines.Env env = (Engines.Env) w[1];
    Engines.TsSource ts = new Engines.OracleTs(cl.oracle);
    Engines.PercoTransfer t = new Engines.PercoTransfer("T1", env, ts, "alice", "bob", 50);
    stepN(t, 0, 6);                       // START, PRE a, PRE b, GETC, WAIT, COMMIT PRIMARY — then pause
    Engines.SnapshotReader r = new Engines.SnapshotReader("R1", env, ts, List.of("alice", "bob"), true);
    stepN(r, 10, 3);                      // snapshot AFTER the primary flip: must see the whole transfer
    boolean rolledForward = env.rollForwards == 1;
    t.step(20);                           // late secondary commit: nothing left to do
    check("perco_reader_finishes_a_half_committed_txn_itself",
        r.sum == 200 && r.seen.get("bob") == 150 && rolledForward && env.fractured == 0
        && cl.of("bob").locks.isEmpty());
  }

  static void percoClientDiesBeforePrimaryFlipRollsBack() {
    Object[] w = twoShardCluster(Engines.Engine.PERCO);
    Core.Cluster cl = (Core.Cluster) w[0]; Engines.Env env = (Engines.Env) w[1];
    Engines.TsSource ts = new Engines.OracleTs(cl.oracle);
    Engines.PercoTransfer t = new Engines.PercoTransfer("T1", env, ts, "alice", "bob", 50);
    t.killAtStep = 5;                     // dies after both prewrites, before the primary flip
    stepN(t, 0, 6);
    Engines.SnapshotReader r = new Engines.SnapshotReader("R1", env, ts, List.of("alice", "bob"), true);
    r.step(10);                           // take snapshot
    Long blocked = Engines.resolveAndRead(env, "alice", r.snapTs, 12);   // within TTL: told to wait
    stepN(r, Engines.LOCK_TTL + 20, 6);   // far past TTL: reader cleans the corpse and proceeds
    check("perco_dead_client_before_flip_gets_rolled_back_by_a_reader",
        t.killed && blocked == null && r.sum == 200 && env.rollBacks >= 1
        && cl.of("alice").locks.isEmpty() && cl.of("bob").locks.isEmpty()
        && cl.totalCommitted() == 200);
  }

  static void percoClientDiesAfterPrimaryFlipRollsForward() {
    Object[] w = twoShardCluster(Engines.Engine.PERCO);
    Core.Cluster cl = (Core.Cluster) w[0]; Engines.Env env = (Engines.Env) w[1];
    Engines.TsSource ts = new Engines.OracleTs(cl.oracle);
    Engines.PercoTransfer t = new Engines.PercoTransfer("T1", env, ts, "alice", "bob", 50);
    t.killAtStep = 6;                     // dies right AFTER the primary flip
    stepN(t, 0, 7);
    Engines.SnapshotReader r = new Engines.SnapshotReader("R1", env, ts, List.of("alice", "bob"), true);
    stepN(r, 10, 3);                      // no waiting needed: the primary knows the verdict
    check("perco_dead_client_after_flip_gets_rolled_FORWARD_by_a_reader",
        t.killed && r.sum == 200 && r.seen.get("bob") == 150 && env.rollForwards == 1
        && cl.totalCommitted() == 200);
  }

  static void percoFirstCommitterWins() {
    Object[] w = twoShardCluster(Engines.Engine.PERCO);
    Core.Cluster cl = (Core.Cluster) w[0]; Engines.Env env = (Engines.Env) w[1];
    Engines.TsSource ts = new Engines.OracleTs(cl.oracle);
    Engines.PercoTransfer t2 = new Engines.PercoTransfer("T2", env, ts, "alice", "bob", 10);
    t2.step(0);                           // T2 takes its snapshot FIRST
    Engines.PercoTransfer t1 = new Engines.PercoTransfer("T1", env, ts, "alice", "bob", 50);
    finish(t1, 1);                        // T1 commits alice meanwhile
    finish(t2, 20);                       // T2's prewrite finds a newer committed version
    check("perco_first_committer_wins_conflict_abort",
        t2.aborted && env.conflictAborts == 1 && cl.of("alice").latest("alice") == 50);
  }

  static void percoWriteSkewAllowed() {
    Core.Cluster cl = new Core.Cluster(2);
    cl.place("oncall_a", 0, 1); cl.place("oncall_b", 1, 1);
    Engines.Env env = new Engines.Env(cl, Engines.Engine.PERCO); env.total = 2;
    Engines.TsSource ts = new Engines.OracleTs(cl.oracle);
    java.util.function.Function<Map<String, Long>, Map<String, Long>> drA =
        reads -> reads.values().stream().mapToLong(Long::longValue).sum() >= 2 ? Map.of("oncall_a", 0L) : Map.of();
    java.util.function.Function<Map<String, Long>, Map<String, Long>> drB =
        reads -> reads.values().stream().mapToLong(Long::longValue).sum() >= 2 ? Map.of("oncall_b", 0L) : Map.of();
    Engines.PercoRmw d1 = new Engines.PercoRmw("D1", env, ts, List.of("oncall_a", "oncall_b"), drA);
    Engines.PercoRmw d2 = new Engines.PercoRmw("D2", env, ts, List.of("oncall_a", "oncall_b"), drB);
    long tick = 0;                        // interleave: both snapshot, both read, both commit
    for (int i = 0; i < 40 && !(d1.done() && d2.done()); i++) { d1.step(tick++); d2.step(tick++); }
    long onCall = cl.of("oncall_a").latest("oncall_a") + cl.of("oncall_b").latest("oncall_b");
    check("perco_is_snapshot_isolation_distributed_write_skew_still_happens",
        env.commits == 2 && onCall == 0);
  }

  static void d2plWriteSkewPrevented() {
    Core.Cluster cl = new Core.Cluster(2);
    cl.place("oncall_a", 0, 1); cl.place("oncall_b", 1, 1);
    Engines.Env env = new Engines.Env(cl, Engines.Engine.TPC_2PL); env.total = 2;
    java.util.function.Function<Map<String, Long>, Map<String, Long>> drA =
        reads -> reads.values().stream().mapToLong(Long::longValue).sum() >= 2 ? Map.of("oncall_a", 0L) : Map.of();
    java.util.function.Function<Map<String, Long>, Map<String, Long>> drB =
        reads -> reads.values().stream().mapToLong(Long::longValue).sum() >= 2 ? Map.of("oncall_b", 0L) : Map.of();
    Engines.TplRmw d1 = new Engines.TplRmw("D1", env, List.of("oncall_a", "oncall_b"), drA);
    Engines.TplRmw d2 = new Engines.TplRmw("D2", env, List.of("oncall_a", "oncall_b"), drB);
    long tick = 0;
    for (int i = 0; i < 80 && !(d1.done() && d2.done()); i++) { d1.step(tick++); d2.step(tick++); }
    // the read->write upgrade deadlocks; a timeout aborts a victim, and whoever survives (or
    // retries serially) re-reads and finds the rule no longer allows a second departure
    Engines.TplRmw d3 = new Engines.TplRmw("D3", env, List.of("oncall_a", "oncall_b"), drA);
    finish(d3, 500);
    Engines.TplRmw d4 = new Engines.TplRmw("D4", env, List.of("oncall_a", "oncall_b"), drB);
    finish(d4, 600);
    long onCall = cl.of("oncall_a").latest("oncall_a") + cl.of("oncall_b").latest("oncall_b");
    check("d2pl_locks_what_was_READ_so_write_skew_cannot_happen",
        (d1.aborted || d2.aborted) && onCall >= 1);
  }

  static void oracleCallsPerTxn() {
    Object[] w = twoShardCluster(Engines.Engine.PERCO);
    Core.Cluster cl = (Core.Cluster) w[0]; Engines.Env env = (Engines.Env) w[1];
    Engines.TsSource ts = new Engines.OracleTs(cl.oracle);
    Engines.PercoTransfer t = new Engines.PercoTransfer("T1", env, ts, "alice", "bob", 50);
    finish(t, 0);
    Engines.SnapshotReader r = new Engines.SnapshotReader("R1", env, ts, List.of("alice", "bob"), true);
    stepN(r, 20, 3);
    check("oracle_two_calls_per_writer_one_per_reader", cl.oracle.calls == 3);
  }

  // ---------------- clocks ----------------

  static void clockSkewStaleRead() {
    Object[] w = twoShardCluster(Engines.Engine.PERCO_CLOCK);
    Engines.Env env = (Engines.Env) w[1];
    Engines.TsSource fast = new Engines.ClockTs(+5, 5, false, 1);   // writer's clock runs 5 ticks fast
    Engines.TsSource slow = new Engines.ClockTs(-5, 5, false, 2);   // reader's clock runs 5 ticks slow
    Engines.PercoTransfer t = new Engines.PercoTransfer("T1", env, fast, "alice", "bob", 50);
    finish(t, 10);                        // fully committed AND acknowledged by tick ~16
    Engines.SnapshotReader r = new Engines.SnapshotReader("R1", env, slow, List.of("alice", "bob"), true);
    stepN(r, t.ackTick + 2, 3);           // starts AFTER the ack — yet reads the past
    check("skewed_clock_reader_misses_an_acknowledged_commit",
        env.staleSnapshots == 1 && r.seen.get("alice") == 100 && r.snapTs < t.commitTs);
  }

  static void clockCommitWaitFixesIt() {
    Object[] w = twoShardCluster(Engines.Engine.PERCO_CLOCK_WAIT);
    Engines.Env env = (Engines.Env) w[1];
    Engines.TsSource fast = new Engines.ClockTs(+5, 5, true, 1);
    Engines.TsSource slow = new Engines.ClockTs(-5, 5, true, 2);
    Engines.PercoTransfer t = new Engines.PercoTransfer("T1", env, fast, "alice", "bob", 50);
    finish(t, 10);
    long waited = env.commitWaitTicks;
    Engines.SnapshotReader r = new Engines.SnapshotReader("R1", env, slow, List.of("alice", "bob"), true);
    stepN(r, t.ackTick + 2, 3);           // starts right after the ack
    check("commit_wait_makes_the_ack_late_enough_to_be_safe",
        env.staleSnapshots == 0 && r.seen.get("alice") == 50 && r.sum == 200);
    check("commit_wait_costs_about_two_epsilon", waited >= 8 && waited <= 13);
  }

  // ---------------- fuzz ----------------

  static void fuzzChecks() {
    Fuzz.Agg naiveKill = Fuzz.runMany(Fuzz.Config.of(Engines.Engine.NAIVE, 42).kills(3), 20);
    check("fuzz_naive_crashes_destroy_money      (" + naiveKill.moneyWrongRuns + "/20 runs)", naiveKill.moneyWrongRuns > 0);

    Fuzz.Agg naive = Fuzz.runMany(Fuzz.Config.of(Engines.Engine.NAIVE, 42), 20);
    check("fuzz_naive_fractured_reads_even_without_crashes (" + naive.fractured + ")", naive.fractured > 0);

    Fuzz.Agg tpcKill = Fuzz.runMany(Fuzz.Config.of(Engines.Engine.TPC, 42).kills(3), 20);
    check("fuzz_tpc_with_recovery_never_loses_money", tpcKill.moneyWrongRuns == 0);

    Fuzz.Agg tpc = Fuzz.runMany(Fuzz.Config.of(Engines.Engine.TPC, 42), 20);
    check("fuzz_tpc_fractured_reads_positive     (" + tpc.fractured + ")", tpc.fractured > 0);

    Fuzz.Agg d2pl = Fuzz.runMany(Fuzz.Config.of(Engines.Engine.TPC_2PL, 42), 20);
    check("fuzz_d2pl_zero_fractured_reads", d2pl.fractured == 0 && d2pl.moneyWrongRuns == 0);

    Fuzz.Agg perco = Fuzz.runMany(Fuzz.Config.of(Engines.Engine.PERCO, 42), 20);
    check("fuzz_perco_zero_fractured_zero_stale", perco.fractured == 0 && perco.staleSnapshots == 0 && perco.moneyWrongRuns == 0);

    Fuzz.Agg percoKill = Fuzz.runMany(Fuzz.Config.of(Engines.Engine.PERCO, 42).kills(3), 20);
    check("fuzz_perco_client_crashes_still_consistent (rf=" + percoKill.rollForwards + " rb=" + percoKill.rollBacks + ")",
        percoKill.moneyWrongRuns == 0 && percoKill.fractured == 0 && percoKill.rollForwards + percoKill.rollBacks > 0);

    Fuzz.Agg d2plKill = Fuzz.runMany(Fuzz.Config.of(Engines.Engine.TPC_2PL, 42).kills(3), 20);
    check("fuzz_dead_coordinators_strand_2pl_readers_but_never_perco_readers ("
            + d2plKill.blockedForever + " vs " + percoKill.blockedForever + ")",
        d2plKill.blockedForever > 0 && percoKill.blockedForever == 0);

    Fuzz.Agg clock = Fuzz.runMany(Fuzz.Config.of(Engines.Engine.PERCO_CLOCK, 42).skewed(8), 20);
    check("fuzz_skewed_clocks_stale_snapshots    (" + clock.staleSnapshots + ")",
        clock.staleSnapshots > 0 && clock.fractured == 0 && clock.moneyWrongRuns == 0);

    Fuzz.Agg wait = Fuzz.runMany(Fuzz.Config.of(Engines.Engine.PERCO_CLOCK_WAIT, 42).skewed(8), 20);
    check("fuzz_commit_wait_zero_stale_snapshots", wait.staleSnapshots == 0 && wait.moneyWrongRuns == 0);

    Fuzz.Config hot = Fuzz.Config.of(Engines.Engine.TPC, 42).sized(2, 30, 0).ticks(500);
    Fuzz.Agg unordered = Fuzz.runMany(hot, 20);
    Fuzz.Agg ordered = Fuzz.runMany(hot.ordered(true), 20);
    check("fuzz_lock_ordering_cuts_timeout_aborts (" + unordered.timeoutAborts + " -> " + ordered.timeoutAborts + ")",
        unordered.timeoutAborts > 0 && ordered.timeoutAborts < unordered.timeoutAborts);
  }

  // ---------------- planted bugs ----------------

  static void brokenSuite() {
    System.out.println("Each planted bug, and the guarantee it breaks:\n");

    // BUG 1: snapshot reader ignores locks
    {
      Object[] w = twoShardCluster(Engines.Engine.PERCO);
      Core.Cluster cl = (Core.Cluster) w[0]; Engines.Env env = (Engines.Env) w[1];
      cl.bugs.readerIgnoresLocks = true;
      Engines.TsSource ts = new Engines.OracleTs(cl.oracle);
      Engines.PercoTransfer t = new Engines.PercoTransfer("T1", env, ts, "alice", "bob", 50);
      stepN(t, 0, 6);                     // primary committed, secondary still locked
      Engines.SnapshotReader r = new Engines.SnapshotReader("R1", env, ts, List.of("alice", "bob"), true);
      stepN(r, 10, 3);
      System.out.println("readerIgnoresLocks:      reader saw sum=" + r.sum + " (should be 200)"
          + " -> ATOMIC VISIBILITY broken (fractured=" + env.fractured + ")");
    }
    // BUG 2: percolator commits secondaries before the primary
    {
      Object[] w = twoShardCluster(Engines.Engine.PERCO);
      Core.Cluster cl = (Core.Cluster) w[0]; Engines.Env env = (Engines.Env) w[1];
      cl.bugs.commitSecondariesFirst = true;
      Engines.TsSource ts = new Engines.OracleTs(cl.oracle);
      Engines.PercoTransfer t = new Engines.PercoTransfer("T1", env, ts, "alice", "bob", 50);
      t.killAtStep = 6;                   // dies between secondary commit and primary commit
      stepN(t, 0, 7);
      Engines.resolveEverything(env);     // lazy resolution: no commit record -> roll the primary back
      System.out.println("commitSecondariesFirst:  final money=" + cl.totalCommitted() + " (should be 200)"
          + " -> ATOMICITY broken: credit committed, debit rolled back");
    }
    // BUG 3: resolver never consults the primary
    {
      Object[] w = twoShardCluster(Engines.Engine.PERCO);
      Core.Cluster cl = (Core.Cluster) w[0]; Engines.Env env = (Engines.Env) w[1];
      cl.bugs.resolveWithoutPrimaryCheck = true;
      Engines.TsSource ts = new Engines.OracleTs(cl.oracle);
      Engines.PercoTransfer t = new Engines.PercoTransfer("T1", env, ts, "alice", "bob", 50);
      t.killAtStep = 6;                   // dies after the primary flip: txn IS committed
      stepN(t, 0, 7);
      Engines.SnapshotReader r = new Engines.SnapshotReader("R1", env, ts, List.of("alice", "bob"), true);
      stepN(r, 10, 3);
      System.out.println("resolveWithoutPrimary:   final money=" + cl.totalCommitted() + " (should be 200)"
          + " -> a COMMITTED write was destroyed by a helpful reader");
    }
    // BUG 4: 2PL reader releases each read lock immediately
    {
      Object[] w = twoShardCluster(Engines.Engine.TPC_2PL);
      Engines.Env env = (Engines.Env) w[1];
      ((Core.Cluster) w[0]).bugs.earlyReadLockRelease = true;
      Engines.LockingReader r = new Engines.LockingReader("R1", env, List.of("alice", "bob"), true);
      Engines.TpcTransfer t = new Engines.TpcTransfer("T1", env, "alice", "bob", 50, true, false);
      r.step(0);                          // reads alice=100, releases the lock at once (the bug)
      finish(t, 1);                       // whole transfer commits in the gap
      r.step(20);                         // reads bob=150
      System.out.println("earlyReadLockRelease:    reader saw sum=" + r.sum + " (should be 200)"
          + " -> strict 2PL became non-strict; fractured=" + env.fractured);
    }
    System.out.println("\n(The healthy suite: java src/Tests.java)");
  }
}
