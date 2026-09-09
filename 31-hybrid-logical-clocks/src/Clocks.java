// Clocks.java — four ways to stamp a moment, one interface.
//
//   WALL     the node's own physical clock reading. Human-meaningful; lies under skew.
//   LAMPORT  a pure event counter (causal-consistency lab). Never lies about order;
//            knows nothing about wall time.
//   NAIVE    the obvious hybrid: one value, max(l+1, pt, incoming+1). Ordered AND
//            near wall time... until load pushes it unboundedly far ahead.
//   HLC      the real thing (Kulkarni/Demirbas 2014): split (l, c) — l never runs
//            ahead of the fastest physical clock you've heard, c counts only the
//            events the wall part cannot tell apart.
import java.util.*;

public class Clocks {
  public enum Kind { WALL, LAMPORT, NAIVE, HLC }

  // ---------- planted-bug switches (EXERCISES rung 6; `java src/Tests.java broken`) ----------
  public static class Bugs {
    public boolean neverResetC = false;      // HLC: c++ even when l advances (never reset to 0)
    public boolean recvIgnoresMsgC = false;  // HLC receive: c' = c+1 in every tie case (drops max with m.c)
    public boolean recvIgnoresPt = false;    // HLC receive: l' = max(l, m.l) — own wall clock not consulted
    public boolean compareIgnoresC = false;  // comparison looks at l only (see Tests; used by the checker)
  }

  public interface Algo {
    Core.Stamp local(int node, long pt);                    // a local step or a send
    Core.Stamp receive(int node, long pt, Core.Stamp msg);  // absorbing a message's stamp
    String name();
  }

  public static Algo make(Kind k, int n) { return make(k, n, new Bugs()); }

  public static Algo make(Kind kind, int n, Bugs bugs) {
    switch (kind) {
      case WALL: return new Algo() {
        public Core.Stamp local(int node, long pt) { return new Core.Stamp(pt, 0); }
        public Core.Stamp receive(int node, long pt, Core.Stamp m) { return new Core.Stamp(pt, 0); }
        public String name() { return "WALL"; }
      };
      case LAMPORT: return new Algo() {
        final long[] l = new long[n];
        public Core.Stamp local(int node, long pt) { return new Core.Stamp(++l[node], 0); }
        public Core.Stamp receive(int node, long pt, Core.Stamp m) {
          l[node] = Math.max(l[node], m.l()) + 1;           // the Lamport rule: never fall behind what you heard
          return new Core.Stamp(l[node], 0);
        }
        public String name() { return "LAMPORT"; }
      };
      case NAIVE: return new Algo() {
        final long[] l = new long[n];
        public Core.Stamp local(int node, long pt) {
          l[node] = Math.max(l[node] + 1, pt);              // +1 ALWAYS — this is the flaw
          return new Core.Stamp(l[node], 0);
        }
        public Core.Stamp receive(int node, long pt, Core.Stamp m) {
          l[node] = Math.max(Math.max(l[node] + 1, m.l() + 1), pt);
          return new Core.Stamp(l[node], 0);
        }
        public String name() { return "NAIVE"; }
      };
      default: return new Algo() {                          // HLC — the paper's Algorithm 2
        final long[] l = new long[n]; final int[] c = new int[n];
        public Core.Stamp local(int node, long pt) {
          long lOld = l[node];
          l[node] = Math.max(lOld, pt);                     // l may only be a wall reading someone took
          if (l[node] == lOld && !bugs.neverResetC) c[node]++;       // wall part stuck: borrow a counter tick
          else if (bugs.neverResetC) c[node]++;
          else c[node] = 0;                                 // wall part advanced: counter owes nothing
          return new Core.Stamp(l[node], c[node]);
        }
        public Core.Stamp receive(int node, long pt, Core.Stamp m) {
          long lOld = l[node];
          long lNew = bugs.recvIgnoresPt ? Math.max(lOld, m.l()) : Math.max(Math.max(lOld, m.l()), pt);
          if (bugs.neverResetC) { l[node] = lNew; c[node]++; return new Core.Stamp(l[node], c[node]); }
          if (lNew == lOld && lNew == m.l())
            c[node] = (bugs.recvIgnoresMsgC ? c[node] : Math.max(c[node], m.c())) + 1;
          else if (lNew == lOld) c[node] = c[node] + 1;
          else if (lNew == m.l()) c[node] = (bugs.recvIgnoresMsgC ? 0 : m.c()) + 1;
          else c[node] = 0;                                 // own wall clock won outright: fresh counter
          l[node] = lNew;
          return new Core.Stamp(l[node], c[node]);
        }
        public String name() { return "HLC"; }
      };
    }
  }

  // ---------- the 64-bit packing real systems use ----------
  /** 48 bits of wall time + 16 bits of counter. Throws when a burst overflows c —
   *  which is exactly what CockroachDB's logical field does (it errors, loudly). */
  public static long pack(Core.Stamp s) {
    if (s.c() > 0xFFFF) throw new IllegalStateException("counter overflow: c=" + s.c() + " > 65535");
    if (s.l() > 0xFFFFFFFFFFFFL) throw new IllegalStateException("wall part exceeds 48 bits");
    return (s.l() << 16) | (s.c() & 0xFFFFL);
  }
  public static Core.Stamp unpack(long packed) {
    return new Core.Stamp(packed >>> 16, (int) (packed & 0xFFFF));
  }
}
