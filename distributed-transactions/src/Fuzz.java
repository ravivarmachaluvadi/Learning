// Fuzz.java — the randomized workload harness: N accounts spread over shards,
// concurrent transfers + full-sweep auditors, optional client crashes, and the
// invariant checks. Every run is fully determined by its seed.
import java.util.*;

public class Fuzz {

  public record Config(Engines.Engine engine, int accounts, int nShards, int transfers, int auditors,
                       long seed, int killEveryNth, long skew, long maxTicks, boolean orderedLocks) {
    public static Config of(Engines.Engine e, long seed) {
      return new Config(e, 12, 3, 20, 8, seed, 0, 0, 3000, false);
    }
    public Config kills(int everyNth) { return new Config(engine, accounts, nShards, transfers, auditors, seed, everyNth, skew, maxTicks, orderedLocks); }
    public Config skewed(long s)      { return new Config(engine, accounts, nShards, transfers, auditors, seed, killEveryNth, s, maxTicks, orderedLocks); }
    public Config ordered(boolean o)  { return new Config(engine, accounts, nShards, transfers, auditors, seed, killEveryNth, skew, maxTicks, o); }
    public Config sized(int acc, int tr, int aud) { return new Config(engine, acc, nShards, tr, aud, seed, killEveryNth, skew, maxTicks, orderedLocks); }
    public Config ticks(long t)       { return new Config(engine, accounts, nShards, transfers, auditors, seed, killEveryNth, skew, t, orderedLocks); }
  }

  public static class RunResult {
    public Engines.Env env;
    public boolean moneyWrong; public long finalSum;
    public int killedTransfers;
    public List<Core.Driver> drivers = new ArrayList<>();
  }

  public static RunResult run(Config c) {
    Core.Cluster cl = new Core.Cluster(c.nShards());
    List<String> accounts = new ArrayList<>();
    for (int i = 0; i < c.accounts(); i++) {
      String k = "a" + i;
      cl.place(k, i % c.nShards(), 100);
      accounts.add(k);
    }
    Engines.Env env = new Engines.Env(cl, c.engine());
    env.total = 100L * c.accounts();

    Core.Sched sched = new Core.Sched(c.seed());
    Random r = new Random(c.seed() * 31 + 7);
    Engines.TsSource oracleTs = new Engines.OracleTs(cl.oracle);
    RunResult res = new RunResult(); res.env = env;
    int clientSeq = 0;

    boolean percoFamily = c.engine() == Engines.Engine.PERCO
        || c.engine() == Engines.Engine.PERCO_CLOCK || c.engine() == Engines.Engine.PERCO_CLOCK_WAIT;
    boolean trueTime = c.engine() == Engines.Engine.PERCO_CLOCK_WAIT;

    for (int i = 0; i < c.transfers(); i++) {
      String from = accounts.get(r.nextInt(accounts.size()));
      String to;
      do { to = accounts.get(r.nextInt(accounts.size())); } while (to.equals(from));
      long amt = 1 + r.nextInt(20);
      String id = "T" + i;
      Engines.Txn t;
      switch (c.engine()) {
        case NAIVE   -> t = new Engines.NaiveTransfer(id, env, from, to, amt);
        case TPC     -> t = new Engines.TpcTransfer(id, env, from, to, amt, false, c.orderedLocks());
        case TPC_2PL -> t = new Engines.TpcTransfer(id, env, from, to, amt, true, c.orderedLocks());
        default -> {
          Engines.TsSource ts = c.engine() == Engines.Engine.PERCO ? oracleTs
              : new Engines.ClockTs(r.nextLong(-c.skew(), c.skew() + 1), c.skew(), trueTime, ++clientSeq);
          t = new Engines.PercoTransfer(id, env, ts, from, to, amt);
        }
      }
      if (c.killEveryNth() > 0 && i % c.killEveryNth() == 0) t.killAtStep = 1 + r.nextInt(7);
      sched.spawnAt(r.nextInt((int) (c.maxTicks() / 2)), t);
      res.drivers.add(t);
    }

    for (int i = 0; i < c.auditors(); i++) {
      List<String> keys = new ArrayList<>(accounts);
      Collections.shuffle(keys, r);
      String id = "R" + i;
      Core.Driver d;
      if (percoFamily) {
        Engines.TsSource ts = c.engine() == Engines.Engine.PERCO ? oracleTs
            : new Engines.ClockTs(r.nextLong(-c.skew(), c.skew() + 1), c.skew(), trueTime, ++clientSeq);
        d = new Engines.SnapshotReader(id, env, ts, keys, true);
      } else if (c.engine() == Engines.Engine.TPC_2PL) {
        d = new Engines.LockingReader(id, env, keys, true);
      } else {
        d = new Engines.LatestReader(id, env, keys, true);
      }
      sched.spawnAt(r.nextInt((int) (c.maxTicks() * 3 / 4)), d);
      res.drivers.add(d);
    }

    sched.run(c.maxTicks());

    // End-of-run: what the world eventually does about leftovers.
    for (Core.Driver d : res.drivers) {
      if (d instanceof Engines.TpcTransfer t) { t.recover(); }
      if (d instanceof Engines.Txn t && t.killed) res.killedTransfers++;
    }
    if (percoFamily) Engines.resolveEverything(env);

    res.finalSum = cl.totalCommitted();
    res.moneyWrong = res.finalSum != env.total;
    return res;
  }

  // ---------- aggregate over many seeds ----------
  public static class Agg {
    public int runs, moneyWrongRuns, killed;
    public long fractured, staleSnapshots, timeoutAborts, conflictAborts, blockedForever,
                readerRestarts, readerWaitTicks, commits, commitLatencyTicks, commitWaitTicks,
                rollForwards, rollBacks, rolledBackByReader, oracleCalls;
    public double avgCommitLatency() { return commits == 0 ? 0 : (double) commitLatencyTicks / commits; }
  }

  public static Agg runMany(Config base, int runs) {
    Agg a = new Agg(); a.runs = runs;
    for (int i = 0; i < runs; i++) {
      Config c = new Config(base.engine(), base.accounts(), base.nShards(), base.transfers(), base.auditors(),
          base.seed() + i * 1000L, base.killEveryNth(), base.skew(), base.maxTicks(), base.orderedLocks());
      RunResult r = run(c);
      if (r.moneyWrong) a.moneyWrongRuns++;
      a.killed += r.killedTransfers;
      Engines.Env e = r.env;
      a.fractured += e.fractured; a.staleSnapshots += e.staleSnapshots;
      a.timeoutAborts += e.timeoutAborts; a.conflictAborts += e.conflictAborts;
      a.blockedForever += e.blockedForever; a.readerRestarts += e.readerRestarts;
      a.readerWaitTicks += e.readerWaitTicks; a.commits += e.commits;
      a.commitLatencyTicks += e.commitLatencyTicks; a.commitWaitTicks += e.commitWaitTicks;
      a.rollForwards += e.rollForwards; a.rollBacks += e.rollBacks;
      a.rolledBackByReader += e.rolledBackByReader;
      a.oracleCalls += e.cluster.oracle.calls;
    }
    return a;
  }
}
