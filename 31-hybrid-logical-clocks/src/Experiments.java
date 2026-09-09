// Experiments.java — the README's numbers, measured live.
//   java src/Experiments.java        -> run all eight
//   java src/Experiments.java 4     -> run one
import java.util.*;

public class Experiments {
  public static void main(String[] args) {
    int only = args.length > 0 ? Integer.parseInt(args[0]) : 0;
    if (only == 0 || only == 1) lies();
    if (only == 0 || only == 2) lamport();
    if (only == 0 || only == 3) naive();
    if (only == 0 || only == 4) bound();
    if (only == 0 || only == 5) cuts();
    if (only == 0 || only == 6) token();
    if (only == 0 || only == 7) badclock();
    if (only == 0 || only == 8) pack();
  }

  // 1 ------------------------------------------------------------------
  static void lies() {
    System.out.println("\n=== 1. lies: how often does each clock stamp cause-and-effect BACKWARDS? ===");
    System.out.println("(inversion = e happened-before f, yet stamp(e) > stamp(f); ties = cannot order them)");
    System.out.printf("  %-6s | %-22s | %-10s | %-10s%n", "skew", "WALL inversions+ties", "LAMPORT", "HLC");
    for (long skew : new long[]{0, 2, 8, 20}) {
      Fuzz.Stats w = Fuzz.run(Fuzz.Config.of(Clocks.Kind.WALL, 42).skewed(skew));
      Fuzz.Stats l = Fuzz.run(Fuzz.Config.of(Clocks.Kind.LAMPORT, 42).skewed(skew));
      Fuzz.Stats h = Fuzz.run(Fuzz.Config.of(Clocks.Kind.HLC, 42).skewed(skew));
      System.out.printf("  %-6d | %5d + %-5d          | %d + %d      | %d + %d%n",
          skew, w.inversions, w.ties, l.inversions, l.ties, h.inversions, h.ties);
    }
    System.out.println("  -> even at skew 0 the wall clock TIES (finite resolution: two events inside one");
    System.out.println("     tick get the same reading). Lamport and HLC never invert and never tie.");
  }

  // 2 ------------------------------------------------------------------
  static void lamport() {
    System.out.println("\n=== 2. lamport: perfectly ordered, and nobody knows what time it is ===");
    Fuzz.Stats s = Fuzz.run(Fuzz.Config.of(Clocks.Kind.LAMPORT, 42));
    System.out.printf("  counter vs wall reading: max +%d ahead, %d behind (drift is UNBOUNDED both ways)%n",
        s.maxDrift, s.minDrift);
    System.out.printf("  events sharing one counter value (±3) span %d ticks of real time%n", s.lamportSpread);
    System.out.println("  -> ask a Lamport clock for 'the database as of 5 p.m.' and there is no answer:");
    System.out.println("     the same counter value happened at different wall times on every node.");
    System.out.println("     (causal-consistency lab, remembered: a Lamport clock is one-way — order only.)");
  }

  // 3 ------------------------------------------------------------------
  static void naive() {
    System.out.println("\n=== 3. naive: one merged value — max(l+1, pt, incoming+1) — looks perfect... ===");
    System.out.printf("  %-10s | %-14s | %-14s%n", "run length", "NAIVE maxDrift", "HLC maxDrift");
    for (long ticks : new long[]{150, 300, 600, 1200}) {
      Fuzz.Stats n = Fuzz.run(new Fuzz.Config(Clocks.Kind.NAIVE, 4, ticks, 8, .2, .15, .05, 12, 42, new Clocks.Bugs()));
      Fuzz.Stats h = Fuzz.run(new Fuzz.Config(Clocks.Kind.HLC, 4, ticks, 8, .2, .15, .05, 12, 42, new Clocks.Bugs()));
      System.out.printf("  %-10d | %-14d | %-14d%n", ticks, n.maxDrift, h.maxDrift);
    }
    System.out.println("  -> the +1 lands on the WALL-VALUED number, so every event pushes the clock");
    System.out.println("     ahead of real time and it never comes back: drift grows with load, forever.");
    System.out.println("     The fix is not a better formula — it is refusing to mix the two quantities.");
  }

  // 4 ------------------------------------------------------------------
  static void bound() {
    System.out.println("\n=== 4. bound: HLC's promise — l minus your wall reading never exceeds the clock spread ===");
    System.out.printf("  %-6s | %-13s | %-14s | %-6s%n", "skew", "clock spread", "measured drift", "maxC");
    for (long skew : new long[]{2, 8, 20, 50}) {
      Fuzz.Stats h = Fuzz.run(Fuzz.Config.of(Clocks.Kind.HLC, 42).skewed(skew));
      System.out.printf("  %-6d | %-13d | %-14d | %-6d%n", skew, 2 * skew, h.maxDrift, h.maxC);
    }
    Fuzz.Stats burst = Fuzz.run(Fuzz.Config.of(Clocks.Kind.HLC, 42).bursty(0.3, 30));
    System.out.printf("  heavy bursts (30 events/tick): drift still %d, and the load went into c (maxC=%d)%n",
        burst.maxDrift, burst.maxC);
    System.out.println("  -> l is always some node's actual wall reading — the fastest one on your causal");
    System.out.println("     path — so it can never stray further than the clocks themselves disagree.");
    System.out.println("     Bursts spill into c, not into l. That split IS the invention.");
  }

  // 5 ------------------------------------------------------------------
  static void cuts() {
    System.out.println("\n=== 5. cuts: 'give me the whole system as of time T' — 200 values of T ===");
    System.out.println("(a cut is torn if it contains a message's receive but not its send)");
    for (Clocks.Kind k : new Clocks.Kind[]{Clocks.Kind.WALL, Clocks.Kind.LAMPORT, Clocks.Kind.HLC}) {
      Fuzz.Stats s = Fuzz.run(Fuzz.Config.of(k, 42));
      System.out.printf("  %-8s torn cuts: %d/%d%s%n", k, s.cutViolations, s.cutsChecked,
          k == Clocks.Kind.LAMPORT ? "   (consistent — but T is a counter value, not a time)" : "");
    }
    System.out.println("  -> distributed-transactions lab, remembered: a snapshot needs one order spanning");
    System.out.println("     all shards. HLC gives a consistent cut AT A WALL-CLOCK TIME — the thing");
    System.out.println("     neither the wall clock (torn) nor Lamport (timeless) could offer.");
  }

  // 6 ------------------------------------------------------------------
  static void token() {
    System.out.println("\n=== 6. token: write through a fast-clock node, read through a slow-clock node ===");
    int[] no = Fuzz.tokenExperiment(Clocks.Kind.HLC, false, 42);
    int[] yes = Fuzz.tokenExperiment(Clocks.Kind.HLC, true, 42);
    int[] wall = Fuzz.tokenExperiment(Clocks.Kind.WALL, true, 42);
    System.out.printf("  no token:              %d/%d reads stale (reader's 'now' predates the write)%n", no[0], no[1]);
    System.out.printf("  carry the stamp along: %d/%d stale — the reader absorbs it via the receive rule%n", yes[0], yes[1]);
    System.out.printf("  wall clock + token:    %d/%d still stale — a clock that ignores input can't be helped%n", wall[0], wall[1]);
    System.out.println("  -> this is MongoDB's clusterTime/afterClusterTime (causal-consistency lab's session");
    System.out.println("     token) — the token is an HLC stamp, and 'absorbing' it is literally receive().");
  }

  // 7 ------------------------------------------------------------------
  static void badclock() {
    System.out.println("\n=== 7. badclock: one node's clock is 60 ticks fast in a cluster that promised ±8 ===");
    long[] off = {60, -8, 0, 3};
    Core.World w = new Core.World(4, off, Clocks.make(Clocks.Kind.HLC, 4), 42);
    for (w.tick = 1; w.tick <= 600; w.tick++) {
      w.deliverDue();
      for (int i = 0; i < 4; i++) {
        if (w.rnd.nextDouble() < 0.2) w.localEvent(i, "work");
        if (w.rnd.nextDouble() < 0.15) w.send(i, (i + 1 + w.rnd.nextInt(3)) % 4, 1 + w.rnd.nextInt(8));
      }
    }
    long worst = 0; for (Core.Event e : w.events) worst = Math.max(worst, e.stamp.l() - e.pt);
    System.out.printf("  drift bound the operators believed: 16.  measured: %d.%n", worst);
    System.out.println("  -> one lying clock infects everyone downstream of it: l is the max wall reading");
    System.out.println("     on your causal path, and this node poisons every path it touches. Ordering");
    System.out.println("     still holds — the BOUND is what died, and every timestamp-vs-time promise");
    System.out.println("     (uncertainty windows, staleness limits) died with it. CockroachDB's answer,");
    System.out.println("     from the distributed-transactions lab: a node past max-offset crashes itself.");
  }

  // 8 ------------------------------------------------------------------
  static void pack() {
    System.out.println("\n=== 8. pack: the whole clock in one 64-bit long ===");
    Core.Stamp s = new Core.Stamp(1724900000123L, 41);
    long packed = Clocks.pack(s);
    System.out.printf("  (l=%d, c=%d) -> %d -> %s (roundtrip ok)%n", s.l(), s.c(), packed, Clocks.unpack(packed));
    System.out.println("  budget: 48 bits of milliseconds ~= 8,900 years; 16 bits of counter = 65,536");
    System.out.println("  causally-chained events per millisecond before overflow (which fails loudly here,");
    System.out.println("  and in CockroachDB). Compare: a vector clock is 8 bytes PER NODE and grows when");
    System.out.println("  the cluster does; an HLC is 8 bytes, full stop — that is why it can live inside");
    System.out.println("  every MVCC version, every message header, every MongoDB oplog entry.");
  }
}
