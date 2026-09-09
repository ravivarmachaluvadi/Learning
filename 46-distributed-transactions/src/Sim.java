// Sim.java — five narrated scenes. Watch the mechanism move, one action at a time.
//   java src/Sim.java        -> all five
//   java src/Sim.java 3      -> just scene 3
import java.util.*;

public class Sim {
  public static void main(String[] args) {
    int only = args.length > 0 ? Integer.parseInt(args[0]) : 0;
    if (only == 0 || only == 1) scene1();
    if (only == 0 || only == 2) scene2();
    if (only == 0 || only == 3) scene3();
    if (only == 0 || only == 4) scene4();
    if (only == 0 || only == 5) scene5();
  }

  static void say(String s) { System.out.println("  " + s); }
  static void head(String s) { System.out.println("\n--- " + s + " ---"); }

  static String shardState(Core.Cluster cl) {
    StringBuilder sb = new StringBuilder();
    for (Core.Shard s : cl.shards) {
      sb.append("      ").append(s.name).append(": ");
      List<String> parts = new ArrayList<>();
      for (Map.Entry<String, Core.Shard> e : cl.owner.entrySet())
        if (e.getValue() == s) parts.add(e.getKey() + "=" + s.latest(e.getKey()));
      sb.append(String.join("  ", parts));
      if (!s.locks.isEmpty()) {
        for (Map.Entry<String, Core.PercoLock> e : s.locks.entrySet())
          sb.append("  LOCK[").append(e.getKey()).append(" by txn@").append(e.getValue().startTs)
            .append(", primary=").append(e.getValue().primaryKey).append("]");
      }
      if (!s.stagedPerco.isEmpty()) sb.append("  staged").append(s.stagedPerco);
      if (!s.writeLock.isEmpty()) sb.append("  writeLock").append(s.writeLock);
      if (!s.commitRecord.isEmpty()) sb.append("  commitRecord").append(s.commitRecord);
      sb.append("\n");
    }
    return sb.toString();
  }

  static Object[] bank(Engines.Engine e) {
    Core.Cluster cl = new Core.Cluster(2);
    cl.place("alice", 0, 100); cl.place("bob", 1, 100);
    Engines.Env env = new Engines.Env(cl, e); env.total = 200;
    return new Object[]{cl, env};
  }

  // ------------------------------------------------------------------
  static void scene1() {
    head("SCENE 1 — Atomic on disk, torn in the eyes: 2PC and the auditor");
    Object[] w = bank(Engines.Engine.TPC);
    Core.Cluster cl = (Core.Cluster) w[0]; Engines.Env env = (Engines.Env) w[1];
    say("alice has 100 on shard S0. bob has 100 on shard S1. Priya moves 50 alice->bob");
    say("with textbook two-phase commit. Omar the auditor sums ALL accounts mid-flight.\n");
    Engines.TpcTransfer t = new Engines.TpcTransfer("T1", env, "alice", "bob", 50, false, false);
    Engines.LatestReader omar = new Engines.LatestReader("omar", env, List.of("alice", "bob"), true);
    t.step(0); say("Priya: PREPARE on S0 — write-lock alice, stage 50. Nothing visible yet.");
    t.step(1); say("Priya: PREPARE on S1 — write-lock bob, stage 150. Nothing visible yet.");
    t.step(2); say("Priya: decision COMMIT logged durably. The transaction WILL happen.");
    t.step(3); say("Priya: COMMIT applied on S0. alice is now 50 — visible to everyone.");
    System.out.print(shardState(cl));
    omar.step(4); say("Omar reads alice on S0: sees 50   (the new world)");
    omar.step(5); say("Omar reads bob on S1:   sees 100  (the old world)");
    say("Omar's total: " + omar.sum + ". Fifty units of money do not exist. fractured=" + env.fractured);
    t.step(6); say("Priya: COMMIT applied on S1. bob is now 150. Total is correct again: " + cl.totalCommitted());
    say("\n  2PC kept its promise — both shards committed, no money lost ON DISK.");
    say("  But 'commit' happened at two different moments, and Omar walked between them.");
    say("  Atomic durability is not atomic VISIBILITY. That gap is this whole lab.");
  }

  // ------------------------------------------------------------------
  static void scene2() {
    head("SCENE 2 — A percolator commit, cell by cell");
    Object[] w = bank(Engines.Engine.PERCO);
    Core.Cluster cl = (Core.Cluster) w[0]; Engines.Env env = (Engines.Env) w[1];
    Engines.PercoTransfer t = new Engines.PercoTransfer("T1", env, new Engines.OracleTs(cl.oracle), "alice", "bob", 50);
    say("Same transfer, percolator style. alice is the PRIMARY (first key written).\n");
    t.step(0); say("start-ts from the oracle: " + t.startTs + " — the snapshot this txn reads from");
    t.step(1); say("PREWRITE alice on S0: lock(alice, start=" + t.startTs + ", primary=alice), stage 50");
    System.out.print(shardState(cl));
    t.step(2); say("PREWRITE bob on S1: lock(bob, start=" + t.startTs + ", primary=alice), stage 150");
    say("   note: bob's lock POINTS AT alice. Every lock knows where the verdict will live.");
    t.step(3); say("commit-ts from the oracle: " + t.commitTs);
    t.step(4); // commit-wait no-op for oracle
    t.step(5); say("COMMIT PRIMARY on S0: in ONE local write — alice=50@" + t.commitTs
        + ", lock removed, commitRecord{" + t.startTs + "->" + t.commitTs + "}");
    say("   *** THAT was the commit point. The whole transaction is now committed, ***");
    say("   *** even though bob's shard has not heard a word about it.            ***");
    System.out.print(shardState(cl));
    t.step(6); say("COMMIT SECONDARY on S1: bob=150@" + t.commitTs + ", lock removed. Pure cleanup.");
    System.out.print(shardState(cl));
    say("Any snapshot at ts>=" + t.commitTs + " sees BOTH writes; any snapshot below sees NEITHER.");
    say("The lock check makes the in-between impossible to observe.");
  }

  // ------------------------------------------------------------------
  static void scene3() {
    head("SCENE 3 — The client dies BEFORE the flip: a reader buries the corpse");
    Object[] w = bank(Engines.Engine.PERCO);
    Core.Cluster cl = (Core.Cluster) w[0]; Engines.Env env = (Engines.Env) w[1];
    Engines.TsSource ts = new Engines.OracleTs(cl.oracle);
    Engines.PercoTransfer t = new Engines.PercoTransfer("T1", env, ts, "alice", "bob", 50);
    t.killAtStep = 5;
    for (int i = 0; i < 6; i++) t.step(i);
    say("Priya prewrote both keys... and her process died before the primary flip.");
    System.out.print(shardState(cl));
    Engines.SnapshotReader omar = new Engines.SnapshotReader("omar", env, ts, List.of("alice", "bob"), true);
    omar.step(10);
    say("Omar takes snapshot " + omar.snapTs + " and finds alice LOCKED.");
    Long v = Engines.resolveAndRead(env, "alice", omar.snapTs, 12);
    say("Omar asks the primary (alice itself): no commit record, lock still fresh -> WAIT (" + v + ")");
    say("...ticks pass; the lock's TTL (" + Engines.LOCK_TTL + " ticks) expires...");
    for (int i = 0; i < 6 && !omar.done(); i++) omar.step(Engines.LOCK_TTL + 15 + i);
    say("Omar: no commit record ever appeared -> the txn never committed. Roll it BACK:");
    say("remove locks and staged values on both shards, then read normally.");
    System.out.print(shardState(cl));
    say("Omar's audit: " + omar.sum + " (correct). Nobody waited for a coordinator to reboot.");
    say("Compare: in lock-based 2PC this exact crash strands every reader (experiment 3: 281 of them).");
  }

  // ------------------------------------------------------------------
  static void scene4() {
    head("SCENE 4 — The client dies AFTER the flip: a reader finishes the commit");
    Object[] w = bank(Engines.Engine.PERCO);
    Core.Cluster cl = (Core.Cluster) w[0]; Engines.Env env = (Engines.Env) w[1];
    Engines.TsSource ts = new Engines.OracleTs(cl.oracle);
    Engines.PercoTransfer t = new Engines.PercoTransfer("T1", env, ts, "alice", "bob", 50);
    t.killAtStep = 6;
    for (int i = 0; i < 7; i++) t.step(i);
    say("Priya flipped the primary — commitRecord{" + t.startTs + "->" + t.commitTs + "} — then died.");
    say("bob's shard still shows a lock and staged data:");
    System.out.print(shardState(cl));
    Engines.SnapshotReader omar = new Engines.SnapshotReader("omar", env, ts, List.of("alice", "bob"), true);
    for (int i = 0; i < 4 && !omar.done(); i++) omar.step(10 + i);
    say("Omar hits bob's lock, follows it to the primary, finds the commit record,");
    say("and ROLLS BOB FORWARD himself: bob=150@" + t.commitTs + ", lock removed.");
    System.out.print(shardState(cl));
    say("Omar's audit: " + omar.sum + " (rollForwards=" + env.rollForwards + ").");
    say("The transaction outlived its own coordinator, because its commit point is DATA.");
  }

  // ------------------------------------------------------------------
  static void scene5() {
    head("SCENE 5 — The clock that lied, and the wait that fixes it");
    Object[] w = bank(Engines.Engine.PERCO_CLOCK);
    Engines.Env env = (Engines.Env) w[1];
    Engines.TsSource fast = new Engines.ClockTs(+5, 5, false, 1);
    Engines.TsSource slow = new Engines.ClockTs(-5, 5, false, 2);
    say("No oracle now — every client stamps time from its own clock. Priya's clock runs");
    say("5 ticks FAST; Omar's runs 5 ticks SLOW. (NTP in real life: milliseconds to seconds.)\n");
    Engines.PercoTransfer t = new Engines.PercoTransfer("T1", env, fast, "alice", "bob", 50);
    for (int i = 10; !t.done(); i++) t.step(i);
    say("tick " + t.ackTick + ": Priya's transfer commits at ts=" + t.commitTs + " and she is told: DONE.");
    Engines.SnapshotReader omar = new Engines.SnapshotReader("omar", env, slow, List.of("alice", "bob"), true);
    long start = t.ackTick + 2;
    for (int i = 0; i < 4 && !omar.done(); i++) omar.step(start + i);
    say("tick " + start + " (AFTER the ack): Omar starts an audit. His slow clock hands him");
    say("snapshot " + omar.snapTs + " — which is LESS than " + t.commitTs + ". He reads alice=" + omar.seen.get("alice") + ".");
    say("A transfer that was confirmed done... is invisible. staleSnapshots=" + env.staleSnapshots);
    say("(the linearizability lab's real-time rule, violated by a wristwatch)\n");

    Object[] w2 = bank(Engines.Engine.PERCO_CLOCK_WAIT);
    Engines.Env env2 = (Engines.Env) w2[1];
    Engines.TsSource fast2 = new Engines.ClockTs(+5, 5, true, 1);
    Engines.TsSource slow2 = new Engines.ClockTs(-5, 5, true, 2);
    Engines.PercoTransfer t2 = new Engines.PercoTransfer("T1", env2, fast2, "alice", "bob", 50);
    for (int i = 10; !t2.done(); i++) t2.step(i);
    say("Rerun with TrueTime commit wait: after choosing commit-ts, Priya's ack is HELD");
    say("until every clock in the system must be past it (" + env2.commitWaitTicks + " ticks ~= 2*eps).");
    Engines.SnapshotReader omar2 = new Engines.SnapshotReader("omar", env2, slow2, List.of("alice", "bob"), true);
    long s2 = t2.ackTick + 2;
    for (int i = 0; i < 4 && !omar2.done(); i++) omar2.step(s2 + i);
    say("Omar starts after the (later) ack: snapshot " + omar2.snapTs + " > commit-ts " + t2.commitTs
        + " -> he sees alice=" + omar2.seen.get("alice") + ". stale=" + env2.staleSnapshots);
    say("Nobody's clock got better. The ACK got slower. That is Spanner's entire trick.");
  }
}
