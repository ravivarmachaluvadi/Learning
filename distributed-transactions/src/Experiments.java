// Experiments.java — the README's numbers, measured live.
//   java src/Experiments.java        -> run all ten
//   java src/Experiments.java 6      -> run one
import java.util.*;

public class Experiments {
  static final int RUNS = 40;

  public static void main(String[] args) {
    int only = args.length > 0 ? Integer.parseInt(args[0]) : 0;
    if (only == 0 || only == 1) torn();
    if (only == 0 || only == 2) fractured();
    if (only == 0 || only == 3) blocking();
    if (only == 0 || only == 4) deadlock();
    if (only == 0 || only == 5) oracle();
    if (only == 0 || only == 6) clocks();
    if (only == 0 || only == 7) writeSkew();
    if (only == 0 || only == 8) latency();
    if (only == 0 || only == 9) resolution();
    if (only == 0 || only == 10) contention();
  }

  static Fuzz.Agg agg(Engines.Engine e, long seed, java.util.function.UnaryOperator<Fuzz.Config> tweak) {
    return Fuzz.runMany(tweak.apply(Fuzz.Config.of(e, seed)), RUNS);
  }

  // 1 ------------------------------------------------------------------
  static void torn() {
    System.out.println("\n=== 1. torn: crash mid-transfer, count runs where money is wrong at the END ===");
    System.out.println("(every 3rd transfer's client is killed at a random step; TPC gets its recovery)");
    Fuzz.Agg naive = agg(Engines.Engine.NAIVE, 42, c -> c.kills(3));
    Fuzz.Agg tpc   = agg(Engines.Engine.TPC, 42, c -> c.kills(3));
    Fuzz.Agg perco = agg(Engines.Engine.PERCO, 42, c -> c.kills(3));
    System.out.printf("  NAIVE  money wrong in %d/%d runs   (each is money created or destroyed, forever)%n", naive.moneyWrongRuns, RUNS);
    System.out.printf("  TPC    money wrong in %d/%d runs   (recovery finishes or undoes every crash)%n", tpc.moneyWrongRuns, RUNS);
    System.out.printf("  PERCO  money wrong in %d/%d runs   (readers finish or undo every crash)%n", perco.moneyWrongRuns, RUNS);
    System.out.println("  -> atomic commit is a solved problem. It is NOT the interesting problem.");
  }

  // 2 ------------------------------------------------------------------
  static void fractured() {
    System.out.println("\n=== 2. fractured: auditors sweep all accounts DURING commits (no crashes at all) ===");
    System.out.println("(a fractured read = an auditor whose sum of all balances is not the true total)");
    for (Engines.Engine e : Engines.Engine.values()) {
      Fuzz.Agg a = agg(e, 42, c -> e.name().startsWith("PERCO_CLOCK") ? c.skewed(8) : c);
      System.out.printf("  %-16s fractured audits: %d%n", e, a.fractured);
    }
    System.out.println("  -> TPC commits atomically and STILL fractures: durability is atomic, visibility is not.");
  }

  // 3 ------------------------------------------------------------------
  static void blocking() {
    System.out.println("\n=== 3. blocking: kill every 2nd coordinator mid-commit; who gets stuck? ===");
    Fuzz.Agg d2pl  = agg(Engines.Engine.TPC_2PL, 42, c -> c.kills(2));
    Fuzz.Agg perco = agg(Engines.Engine.PERCO, 42, c -> c.kills(2));
    System.out.printf("  TPC_2PL readers who gave up waiting: %d   (wait ticks: %d)%n", d2pl.blockedForever, d2pl.readerWaitTicks);
    System.out.printf("  PERCO   readers who gave up waiting: %d   (wait ticks: %d, roll-forwards by readers: %d, roll-backs: %d)%n",
        perco.blockedForever, perco.readerWaitTicks, perco.rollForwards, perco.rollBacks);
    System.out.println("  -> 2PC's blocking problem, inherited by innocent READERS when locks guard reads.");
    System.out.println("     Percolator readers consult the primary's commit record and finish the job themselves.");
  }

  // 4 ------------------------------------------------------------------
  static void deadlock() {
    System.out.println("\n=== 4. deadlock: 30 transfers between TWO hot accounts on different shards ===");
    Fuzz.Config hot = Fuzz.Config.of(Engines.Engine.TPC, 42).sized(2, 30, 0).ticks(500);
    Fuzz.Agg unordered = Fuzz.runMany(hot, RUNS);
    Fuzz.Agg ordered   = Fuzz.runMany(hot.ordered(true), RUNS);
    System.out.printf("  lock in access order:  %d timeout aborts, %d commits%n", unordered.timeoutAborts, unordered.commits);
    System.out.printf("  lock in sorted order:  %d timeout aborts, %d commits%n", ordered.timeoutAborts, ordered.commits);
    System.out.println("  -> the deadlock is invisible: each shard sees ONE waiting txn, no cycle. Nobody can");
    System.out.println("     detect it locally — timeouts (or a global waits-for graph) are the only ways out.");
    System.out.println("     The isolation-levels fix — one agreed lock order — still works across shards.");
  }

  // 5 ------------------------------------------------------------------
  static void oracle() {
    System.out.println("\n=== 5. oracle: what the global timestamp counter costs ===");
    Fuzz.Agg a = agg(Engines.Engine.PERCO, 42, c -> c);
    System.out.printf("  %d oracle calls across %d runs (%d commits + aborted txns + 1 per reader)%n",
        a.oracleCalls, RUNS, a.commits);
    System.out.println("  -> ~2 calls per write txn, 1 per read txn. EVERY transaction in the whole cluster");
    System.out.println("     queues at this one counter: a single point of failure and of scale (TiDB ships");
    System.out.println("     exactly this as its PD timestamp service). Removing it is experiment 6.");
  }

  // 6 ------------------------------------------------------------------
  static void clocks() {
    System.out.println("\n=== 6. clocks: replace the oracle with each client's own clock ===");
    System.out.println("(stale snapshot = a reader that started AFTER a commit was acknowledged, yet cannot see it)");
    System.out.printf("  %-8s | %-28s | %-28s%n", "skew", "trust the clock (no wait)", "TrueTime commit wait");
    for (long skew : new long[]{0, 2, 8, 20}) {
      final long s = skew;
      Fuzz.Agg raw  = agg(Engines.Engine.PERCO_CLOCK, 42, c -> c.skewed(s));
      Fuzz.Agg wait = agg(Engines.Engine.PERCO_CLOCK_WAIT, 42, c -> c.skewed(s));
      System.out.printf("  %-8d | %3d stale, lat %5.1f ticks   | %3d stale, lat %5.1f ticks%n",
          skew, raw.staleSnapshots, raw.avgCommitLatency(), wait.staleSnapshots, wait.avgCommitLatency());
    }
    System.out.println("  -> without the wait, staleness grows with skew; with it, zero stale — paid for in");
    System.out.println("     latency that grows with the uncertainty bound. Spanner's bet: make eps tiny with");
    System.out.println("     GPS+atomic clocks, then commit-wait is only a few ms.");
  }

  // 7 ------------------------------------------------------------------
  static void writeSkew() {
    System.out.println("\n=== 7. writeskew: the isolation-levels anomaly, now spread across two shards ===");
    // percolator: both doctors read {1,1} under their snapshots, write DIFFERENT keys, both commit
    Core.Cluster cl = new Core.Cluster(2);
    cl.place("oncall_a", 0, 1); cl.place("oncall_b", 1, 1);
    Engines.Env env = new Engines.Env(cl, Engines.Engine.PERCO); env.total = 2;
    Engines.TsSource ts = new Engines.OracleTs(cl.oracle);
    java.util.function.Function<Map<String, Long>, Map<String, Long>> drA =
        r -> r.values().stream().mapToLong(Long::longValue).sum() >= 2 ? Map.of("oncall_a", 0L) : Map.of();
    java.util.function.Function<Map<String, Long>, Map<String, Long>> drB =
        r -> r.values().stream().mapToLong(Long::longValue).sum() >= 2 ? Map.of("oncall_b", 0L) : Map.of();
    Engines.PercoRmw d1 = new Engines.PercoRmw("D1", env, ts, List.of("oncall_a", "oncall_b"), drA);
    Engines.PercoRmw d2 = new Engines.PercoRmw("D2", env, ts, List.of("oncall_a", "oncall_b"), drB);
    long tick = 0;
    for (int i = 0; i < 40 && !(d1.done() && d2.done()); i++) { d1.step(tick++); d2.step(tick++); }
    long onCall = cl.of("oncall_a").latest("oncall_a") + cl.of("oncall_b").latest("oncall_b");
    System.out.printf("  PERCO (snapshot isolation): both committed, doctors on call = %d  <- rule was 'at least 1'%n", onCall);
    System.out.println("  TPC_2PL: read locks collide on the upgrade, one aborts, rule holds (see Tests)");
    System.out.println("  -> percolator = snapshot isolation, distributed. Write skew survives the distribution");
    System.out.println("     untouched. CockroachDB adds SSI-style read tracking on top to kill it (at abort cost).");
  }

  // 8 ------------------------------------------------------------------
  static void latency() {
    System.out.println("\n=== 8. latency: what each guarantee costs per committed transfer (ticks) ===");
    for (Engines.Engine e : Engines.Engine.values()) {
      Fuzz.Agg a = agg(e, 42, c -> e.name().startsWith("PERCO_CLOCK") ? c.skewed(8) : c);
      System.out.printf("  %-16s commit latency %5.1f   reader wait ticks %5d%n", e, a.avgCommitLatency(), a.readerWaitTicks);
    }
    System.out.println("  -> every rung of correctness is paid for in someone's time: writers (commit wait),");
    System.out.println("     readers (lock waits), or both. Nothing here is free.");
  }

  // 9 ------------------------------------------------------------------
  static void resolution() {
    System.out.println("\n=== 9. resolution: kill a percolator client at EVERY step; who wins? ===");
    System.out.println("  step killed at                      | outcome after readers resolve      | money");
    String[] names = {
        "1 after start-ts               ", "2 after prewrite primary       ", "3 after prewrite secondary     ",
        "4 after get commit-ts          ", "5 after commit-wait check      ", "6 after PRIMARY COMMIT (flip)  ",
        "7 after secondary commit       "};
    for (int k = 1; k <= 7; k++) {
      Core.Cluster cl = new Core.Cluster(2);
      cl.place("alice", 0, 100); cl.place("bob", 1, 100);
      Engines.Env env = new Engines.Env(cl, Engines.Engine.PERCO); env.total = 200;
      Engines.PercoTransfer t = new Engines.PercoTransfer("T1", env, new Engines.OracleTs(cl.oracle), "alice", "bob", 50);
      t.killAtStep = k;
      for (int i = 0; i < 10; i++) t.step(i);
      Engines.resolveEverything(env);
      long sum = cl.totalCommitted();
      boolean committed = cl.of("alice").latest("alice") == 50;
      System.out.printf("  %s | %-34s | %d%n", names[k - 1],
          committed ? "ROLLED FORWARD (txn committed)" : "rolled back (txn never happened)", sum);
    }
    System.out.println("  -> one single step — the primary flip — separates 'never happened' from 'fully");
    System.out.println("     happened'. The commit point is a ROW IN A SHARD, not a coordinator's memory,");
    System.out.println("     which is why nobody has to wait for a dead coordinator to wake up.");
  }

  // 10 -----------------------------------------------------------------
  static void contention() {
    System.out.println("\n=== 10. contention: optimistic vs pessimistic on two hot accounts ===");
    Fuzz.Config hot = Fuzz.Config.of(Engines.Engine.PERCO, 42).sized(2, 30, 0).ticks(500);
    Fuzz.Agg perco = Fuzz.runMany(hot, RUNS);
    Fuzz.Agg d2pl  = Fuzz.runMany(Fuzz.Config.of(Engines.Engine.TPC_2PL, 42).sized(2, 30, 0).ticks(500), RUNS);
    System.out.printf("  PERCO   (optimistic):  %4d commits, %4d conflict aborts, %4d timeout aborts%n",
        perco.commits, perco.conflictAborts, perco.timeoutAborts);
    System.out.printf("  TPC_2PL (pessimistic): %4d commits, %4d conflict aborts, %4d timeout aborts%n",
        d2pl.commits, d2pl.conflictAborts, d2pl.timeoutAborts);
    System.out.println("  -> same trade as isolation-levels, distributed: optimistic pays in RETRIES at commit,");
    System.out.println("     pessimistic pays in WAITING (and cross-shard deadlocks). Contention is still a");
    System.out.println("     data-access-pattern property — spread the keys and both columns collapse.");
  }
}
