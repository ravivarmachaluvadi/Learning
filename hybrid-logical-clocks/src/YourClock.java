// YourClock.java — EXERCISES rung 5: implement the HLC receive rule yourself.
//
// Your node's clock is (l, c). A message arrives carrying the sender's stamp
// (ml, mc), and your wall clock currently reads pt. Produce the stamp for the
// receive event. Two duties, in tension:
//   1. the receive must be stamped AFTER the send and AFTER your own last event
//   2. l may only ever be a wall reading somebody actually took (that is the
//      whole drift bound) — c absorbs everything l cannot express
//
// Fill in receive(), then run:
//   java src/YourClock.java      (self-checks 6 scenarios; the blank version scores 1/6)
//
// The real rule is Clocks.make(HLC).receive — don't peek until you've tried.
public class YourClock {

  static long l = 0; static int c = 0;   // your node's clock state

  static Core.Stamp receive(long pt, long ml, int mc) {
    // ======== YOUR CODE HERE (replace the two lines below) ========
    l = Math.max(l, Math.max(ml, pt));
    c = 0;
    // ==============================================================
    return new Core.Stamp(l, c);
  }

  record Case(String story, long l0, int c0, long pt, long ml, int mc, Core.Stamp want) {}

  public static void main(String[] args) {
    Case[] cases = {
      new Case("your wall clock is ahead of everyone: pt=30, own (20,9), msg (25,3)",
               20, 9, 30, 25, 3, new Core.Stamp(30, 0)),
      new Case("the message is from the future: own (5,2), msg (20,3), pt=11",
               5, 2, 11, 20, 3, new Core.Stamp(20, 4)),
      new Case("your own l leads: own (40,6), msg (35,9), pt=38",
               40, 6, 38, 35, 9, new Core.Stamp(40, 7)),
      new Case("three-way tie at 50: own (50,2), msg (50,7), pt=50",
               50, 2, 50, 50, 7, new Core.Stamp(50, 8)),
      new Case("tie between own l and msg at 60, wall behind: own (60,4), msg (60,1), pt=55",
               60, 4, 55, 60, 1, new Core.Stamp(60, 5)),
      new Case("everything equal and quiet: own (10,0), msg (10,0), pt=10",
               10, 0, 10, 10, 0, new Core.Stamp(10, 1)),
    };
    int ok = 0;
    for (Case k : cases) {
      l = k.l0; c = k.c0;
      Core.Stamp got = receive(k.pt, k.ml, k.mc);
      boolean right = got.equals(k.want);
      if (right) ok++;
      System.out.printf("  %s %s%n      you: %-10s expected: %s%n",
          right ? "ok " : "X  ", k.story, got, right ? "(right)" : k.want);
    }
    System.out.println("\n  " + ok + "/6 correct");
    if (ok < 6) System.out.println("""
        Hints: WHEN is it safe to reset c to zero? (Only when l genuinely moved past
        everything it must dominate.) When l TIES, whose counter must the new c beat —
        yours, the message's, or both? Getting case 4 right and case 3 wrong (or the
        reverse) points at exactly which tie you are mishandling.""");
    else System.out.println("  That's Algorithm 2 of the HLC paper — the receive path of CockroachDB's clock.");
  }
}
