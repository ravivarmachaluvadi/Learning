// Core.java — shared machinery: a deterministic multi-node world with skewed
// physical clocks, messages in flight, and a ground-truth causality checker.
//
// The ground truth is a VECTOR CLOCK (known from the causal-consistency lab):
// it decides definitively whether event e happened-before event f. Every scalar
// clock design in this lab is then judged against that truth: does it order
// every happens-before pair correctly, and how far does it stray from wall time?
import java.util.*;

public class Core {

  /** One timestamp: l = wall-time part, c = logical counter part. Wall and
   *  Lamport clocks use c=0. Compared lexicographically — (l,c) is the whole
   *  identity of a moment. */
  public record Stamp(long l, int c) implements Comparable<Stamp> {
    public int compareTo(Stamp o) { return l != o.l ? Long.compare(l, o.l) : Integer.compare(c, o.c); }
    public boolean before(Stamp o) { return compareTo(o) < 0; }
    public String toString() { return c == 0 ? String.valueOf(l) : l + "." + c; }
  }

  /** One recorded event, with the scalar stamp under test AND the vector-clock truth. */
  public static class Event {
    public final int id, node; public final String kind; public final long tick;
    public final Stamp stamp; public final int[] vc; public final long pt;
    public int sendId = -1;                       // for receives: the matching send event
    public Event(int id, int node, String kind, long tick, long pt, Stamp stamp, int[] vc) {
      this.id = id; this.node = node; this.kind = kind; this.tick = tick; this.pt = pt;
      this.stamp = stamp; this.vc = vc;
    }
  }
  /** vector-clock happens-before: e hb f  iff  vc(e) < vc(f) componentwise-with-one-strict. */
  public static boolean hb(Event e, Event f) {
    boolean strict = false;
    for (int i = 0; i < e.vc.length; i++) {
      if (e.vc[i] > f.vc[i]) return false;
      if (e.vc[i] < f.vc[i]) strict = true;
    }
    return strict;
  }

  /** A message in flight: carries the sender's stamp (what real systems piggyback)
   *  and the sender's vector clock (for the checker only — real systems don't ship it). */
  public static class Msg {
    public final int from, to; public final long deliverAt;
    public final Stamp stamp; public final int[] vc; public final int sendEventId;
    public Msg(int from, int to, long deliverAt, Stamp stamp, int[] vc, int sendEventId) {
      this.from = from; this.to = to; this.deliverAt = deliverAt; this.stamp = stamp; this.vc = vc; this.sendEventId = sendEventId;
    }
  }

  /** The world: n nodes, each with a physical clock offset (its lie), one clock
   *  algorithm under test, and a deterministic seeded schedule. */
  public static class World {
    public final int n; public final long[] offset; public long tick = 0;
    public final Clocks.Algo algo;
    public final List<Event> events = new ArrayList<>();
    public final List<Msg> inFlight = new ArrayList<>();
    public final int[][] vc;                       // per-node vector clock (ground truth)
    public final Random rnd;
    public World(int n, long[] offset, Clocks.Algo algo, long seed) {
      this.n = n; this.offset = offset; this.algo = algo; this.rnd = new Random(seed);
      vc = new int[n][n];
    }
    /** What node i's wall clock READS right now — true time plus its lie. */
    public long pt(int i) { return tick + offset[i]; }

    public Event localEvent(int i, String kind) {
      vc[i][i]++;
      Stamp s = algo.local(i, pt(i));
      Event e = new Event(events.size(), i, kind, tick, pt(i), s, vc[i].clone());
      events.add(e); return e;
    }
    public Event send(int from, int to, long delay) {
      Event e = localEvent(from, "send→" + to);
      inFlight.add(new Msg(from, to, tick + delay, e.stamp, e.vc, e.id));
      return e;
    }
    public List<Event> deliverDue() {
      List<Event> delivered = new ArrayList<>();
      for (Iterator<Msg> it = inFlight.iterator(); it.hasNext(); ) {
        Msg m = it.next();
        if (m.deliverAt <= tick) {
          it.remove();
          int i = m.to;
          for (int k = 0; k < n; k++) vc[i][k] = Math.max(vc[i][k], m.vc[k]);   // merge, then
          vc[i][i]++;                                                            // step — ground truth
          Stamp s = algo.receive(i, pt(i), m.stamp);
          Event e = new Event(events.size(), i, "recv←" + m.from, tick, pt(i), s, vc[i].clone());
          e.sendId = m.sendEventId;
          events.add(e); delivered.add(e);
        }
      }
      return delivered;
    }
  }
}
