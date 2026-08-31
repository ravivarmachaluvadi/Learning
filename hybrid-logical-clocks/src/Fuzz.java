// Fuzz.java — randomized workloads + the metrics every claim in the README rests on.
// A run is fully determined by its seed. Metrics:
//   inversions  — happens-before pairs the clock stamped in the WRONG order
//   ties        — happens-before pairs the clock cannot tell apart
//   maxDrift    — max (stamp.l − node's wall reading): how far timestamps stray from wall time
//   maxC        — largest logical counter seen (HLC only)
//   cutViolations — "snapshot at time T" cuts that caught a receive without its send
import java.util.*;

public class Fuzz {

  public record Config(Clocks.Kind kind, int nodes, long ticks, long skew,
                       double eventProb, double sendProb, double burstProb, int burstSize,
                       long seed, Clocks.Bugs bugs) {
    public static Config of(Clocks.Kind k, long seed) {
      return new Config(k, 4, 600, 8, 0.20, 0.15, 0.05, 12, seed, new Clocks.Bugs());
    }
    public Config skewed(long s)      { return new Config(kind, nodes, ticks, s, eventProb, sendProb, burstProb, burstSize, seed, bugs); }
    public Config bursty(double p, int b) { return new Config(kind, nodes, ticks, skew, eventProb, sendProb, p, b, seed, bugs); }
    public Config bugged(Clocks.Bugs b) { return new Config(kind, nodes, ticks, skew, eventProb, sendProb, burstProb, burstSize, seed, b); }
  }

  public static class Stats {
    public int events, hbPairsChecked, inversions, ties;
    public long maxDrift = Long.MIN_VALUE, minDrift = Long.MAX_VALUE;
    public int maxC;
    public int cutsChecked, cutViolations;
    public long lamportSpread;    // wall-tick spread among events with near-equal counter values
  }

  public static Core.World build(Config c) {
    Random setup = new Random(c.seed() * 31 + 5);
    long[] off = new long[c.nodes()];
    for (int i = 0; i < c.nodes(); i++) off[i] = c.skew() == 0 ? 0 : setup.nextLong(-c.skew(), c.skew() + 1);
    // pin the extremes so "skew" always means what it says: someone IS that wrong
    if (c.nodes() >= 2 && c.skew() > 0) { off[0] = c.skew(); off[1] = -c.skew(); }
    return new Core.World(c.nodes(), off, Clocks.make(c.kind(), c.nodes(), c.bugs()), c.seed());
  }

  public static Stats run(Config c) {
    Core.World w = build(c);
    for (w.tick = 1; w.tick <= c.ticks(); w.tick++) {
      w.deliverDue();
      for (int i = 0; i < c.nodes(); i++) {
        if (w.rnd.nextDouble() < c.burstProb())
          for (int b = 0; b < c.burstSize(); b++) w.localEvent(i, "burst");   // many events, one clock tick
        if (w.rnd.nextDouble() < c.eventProb()) w.localEvent(i, "work");
        if (w.rnd.nextDouble() < c.sendProb())
          w.send(i, (i + 1 + w.rnd.nextInt(c.nodes() - 1)) % c.nodes(), 1 + w.rnd.nextInt(8));
      }
    }
    w.tick = c.ticks(); // deliver stragglers
    while (!w.inFlight.isEmpty()) { w.tick++; w.deliverDue(); }
    return measure(w, c);
  }

  static Stats measure(Core.World w, Config c) {
    Stats s = new Stats();
    List<Core.Event> ev = w.events;
    s.events = ev.size();
    for (Core.Event e : ev) {
      long drift = e.stamp.l() - e.pt;
      s.maxDrift = Math.max(s.maxDrift, drift); s.minDrift = Math.min(s.minDrift, drift);
      s.maxC = Math.max(s.maxC, e.stamp.c());
    }
    // 1) order: sample pairs, judge against the vector-clock truth
    Random r = new Random(c.seed() * 31 + 11);
    for (int k = 0; k < 60000 && ev.size() > 1; k++) {
      Core.Event a = ev.get(r.nextInt(ev.size())), b = ev.get(r.nextInt(ev.size()));
      if (a == b) continue;
      if (Core.hb(a, b)) {
        s.hbPairsChecked++;
        int cmp = c.bugs().compareIgnoresC ? Long.compare(a.stamp.l(), b.stamp.l()) : a.stamp.compareTo(b.stamp);
        if (cmp > 0) s.inversions++; else if (cmp == 0) s.ties++;
      }
    }
    // 2) snapshot cuts: "give me the world as of stamp (T, max)" — consistent iff no
    //    receive is inside while its send is outside
    long lo = ev.stream().mapToLong(e -> e.stamp.l()).min().orElse(0);
    long hi = ev.stream().mapToLong(e -> e.stamp.l()).max().orElse(0);
    Map<Integer, Core.Event> byId = new HashMap<>();
    for (Core.Event e : ev) byId.put(e.id, e);
    for (int k = 0; k < 200; k++) {
      long T = lo + (hi - lo) * k / 200;
      Core.Stamp cut = new Core.Stamp(T, Integer.MAX_VALUE);
      boolean bad = false;
      for (Core.Event e : ev)
        if (e.sendId >= 0 && e.stamp.compareTo(cut) <= 0 && byId.get(e.sendId).stamp.compareTo(cut) > 0) { bad = true; break; }
      s.cutsChecked++; if (bad) s.cutViolations++;
    }
    // 3) how far apart in WALL time are events with near-equal stamp values?
    //    (for Lamport this spread is the "you cannot ask for 5 p.m." number)
    long mid = (lo + hi) / 2;
    long tmin = Long.MAX_VALUE, tmax = Long.MIN_VALUE;
    for (Core.Event e : ev) if (Math.abs(e.stamp.l() - mid) <= 3) { tmin = Math.min(tmin, e.tick); tmax = Math.max(tmax, e.tick); }
    s.lamportSpread = (tmax >= tmin) ? tmax - tmin : 0;
    return s;
  }

  /** The causality-token experiment: a client writes via node A (fast clock) and
   *  immediately reads via node B (slow clock). Without carrying A's stamp to B,
   *  B's "as of my current clock" snapshot predates the write. With the token —
   *  B absorbs the stamp through the ordinary receive rule — staleness is impossible. */
  public static int[] tokenExperiment(Clocks.Kind kind, boolean useToken, long seed) {
    Config c = Config.of(kind, seed);
    Core.World w = build(c);
    int stale = 0, total = 0;
    for (w.tick = 1; w.tick <= 400; w.tick++) {
      w.deliverDue();
      if (w.rnd.nextDouble() < 0.25) {
        int a = 0, b = 1;                                  // A runs +skew fast, B runs −skew slow (pinned in build)
        Core.Event write = w.localEvent(a, "write");
        if (useToken) w.algo.receive(b, w.pt(b), write.stamp);       // the token: B absorbs A's stamp
        Core.Event read = w.localEvent(b, "read");
        total++;
        if (read.stamp.compareTo(write.stamp) < 0) stale++;          // read's snapshot predates the write
      }
      if (w.rnd.nextDouble() < 0.2) w.send(w.rnd.nextInt(w.n), w.rnd.nextInt(w.n), 1 + w.rnd.nextInt(6));
    }
    return new int[]{stale, total};
  }
}
