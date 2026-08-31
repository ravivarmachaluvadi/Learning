// Tests.java — every claim the README makes, executed.
//   java src/Tests.java          -> the full suite
//   java src/Tests.java broken   -> enable each planted bug and show which guarantee it breaks
import java.util.*;

public class Tests {
  static int pass = 0, fail = 0;
  static List<String> failures = new ArrayList<>();
  static void check(String name, boolean ok) {
    if (ok) { pass++; System.out.println("  ok  " + name); }
    else { fail++; failures.add(name); System.out.println("  FAIL " + name); }
  }

  public static void main(String[] args) {
    if (args.length > 0 && args[0].equals("broken")) { brokenSuite(); return; }

    System.out.println("== the HLC update rules, case by case (paper Algorithm 2) ==");
    ruleTests();
    System.out.println("== WALL: human-meaningful, causally wrong ==");
    wallTests();
    System.out.println("== LAMPORT & NAIVE: causally right, unmoored from wall time ==");
    lamportNaiveTests();
    System.out.println("== HLC: both, with a bounded price ==");
    hlcTests();
    System.out.println("== causality tokens & the 64-bit packing ==");
    tokenAndPackTests();

    System.out.println();
    System.out.println(pass + " passed, " + fail + " failed" + (fail > 0 ? " -> " + failures : ""));
    if (fail > 0) System.exit(1);
  }

  // ---------------- rules ----------------
  static void ruleTests() {
    Clocks.Algo a = Clocks.make(Clocks.Kind.HLC, 1);
    // wall ahead of everything -> adopt it, counter owes nothing
    check("local_wall_advanced_resets_c", a.local(0, 12).equals(new Core.Stamp(12, 0)));
    // wall stuck at 12 -> borrow a counter tick
    check("local_wall_stuck_increments_c", a.local(0, 12).equals(new Core.Stamp(12, 1)));
    // receive, three-way tie on l=12: counter jumps past BOTH sides' counters
    check("receive_three_way_tie_takes_max_c_plus_1",
        a.receive(0, 12, new Core.Stamp(12, 7)).equals(new Core.Stamp(12, 8)));
    // message from the future (its l wins): adopt its l, its c + 1
    check("receive_msg_ahead_adopts_l_and_c_plus_1",
        a.receive(0, 11, new Core.Stamp(20, 3)).equals(new Core.Stamp(20, 4)));
    // own wall wins outright: fresh start, c = 0
    check("receive_own_wall_ahead_resets_c",
        a.receive(0, 30, new Core.Stamp(20, 9)).equals(new Core.Stamp(30, 0)));
    check("stamp_comparison_is_lexicographic",
        new Core.Stamp(10, 2).before(new Core.Stamp(10, 3))
        && new Core.Stamp(10, 99).before(new Core.Stamp(11, 0)));
    // ground-truth sanity: the vector-clock checker itself
    Core.World w = new Core.World(2, new long[]{0, 0}, Clocks.make(Clocks.Kind.HLC, 2), 1);
    w.tick = 1; Core.Event s = w.send(0, 1, 1);
    Core.Event x = w.localEvent(1, "concurrent-with-send");
    w.tick = 2; Core.Event r = w.deliverDue().get(0);
    check("vector_clock_truth_send_hb_receive_and_concurrency",
        Core.hb(s, r) && !Core.hb(r, s) && !Core.hb(s, x) && !Core.hb(x, s));
  }

  // ---------------- WALL ----------------
  static void wallTests() {
    Fuzz.Stats s = Fuzz.run(Fuzz.Config.of(Clocks.Kind.WALL, 42));
    check("wall_inverts_happens_before          (" + s.inversions + " of " + s.hbPairsChecked + " hb pairs)", s.inversions > 0);
    check("wall_snapshot_cuts_torn              (" + s.cutViolations + "/" + s.cutsChecked + ")", s.cutViolations > 100);
    Fuzz.Stats z = Fuzz.run(Fuzz.Config.of(Clocks.Kind.WALL, 42).skewed(0));
    check("wall_zero_skew_no_inversions_but_ties (" + z.ties + " ties)", z.inversions == 0 && z.ties > 0);
  }

  // ---------------- LAMPORT & NAIVE ----------------
  static void lamportNaiveTests() {
    Fuzz.Stats lam = Fuzz.run(Fuzz.Config.of(Clocks.Kind.LAMPORT, 42));
    check("lamport_never_inverts_never_ties", lam.inversions == 0 && lam.ties == 0);
    check("lamport_counter_unmoored_from_wall   (drift +" + lam.maxDrift + " / " + lam.minDrift + ")",
        lam.maxDrift > 100 && lam.minDrift < 0);
    check("lamport_same_value_spans_" + lam.lamportSpread + "_ticks_of_wall_time", lam.lamportSpread > 10);
    Fuzz.Stats n300 = Fuzz.run(new Fuzz.Config(Clocks.Kind.NAIVE, 4, 300, 8, .2, .15, .05, 12, 42, new Clocks.Bugs()));
    Fuzz.Stats n600 = Fuzz.run(Fuzz.Config.of(Clocks.Kind.NAIVE, 42));
    check("naive_ordered_but_drift_grows_with_load (" + n300.maxDrift + " @300 ticks -> " + n600.maxDrift + " @600)",
        n600.inversions == 0 && n300.maxDrift > 100 && n600.maxDrift > n300.maxDrift + 100);
  }

  // ---------------- HLC ----------------
  static void hlcTests() {
    Fuzz.Stats s = Fuzz.run(Fuzz.Config.of(Clocks.Kind.HLC, 42));
    check("hlc_zero_inversions_zero_ties", s.inversions == 0 && s.ties == 0);
    check("hlc_snapshot_cuts_always_consistent  (0/" + s.cutsChecked + ")", s.cutViolations == 0);
    check("hlc_l_never_below_own_wall_reading", s.minDrift == 0);
    boolean bounded = true;
    for (long skew : new long[]{4, 8, 20}) {
      Fuzz.Stats k = Fuzz.run(Fuzz.Config.of(Clocks.Kind.HLC, 42).skewed(skew));
      if (k.maxDrift > 2 * skew) bounded = false;
    }
    check("hlc_drift_bounded_by_clock_spread_at_every_skew", bounded);
    Fuzz.Stats burst = Fuzz.run(Fuzz.Config.of(Clocks.Kind.HLC, 42).bursty(0.3, 30));
    check("hlc_bursts_go_into_c_not_into_l      (maxC=" + burst.maxC + ", drift still " + burst.maxDrift + ")",
        burst.maxC > 30 && burst.maxDrift <= 16 && burst.inversions == 0);
    // c resets whenever the wall part advances, so it stays tiny under normal load
    check("hlc_maxC_small_relative_to_events    (maxC=" + s.maxC + " over " + s.events + " events)",
        s.maxC < 60);
  }

  // ---------------- tokens & packing ----------------
  static void tokenAndPackTests() {
    int[] no = Fuzz.tokenExperiment(Clocks.Kind.HLC, false, 42);
    int[] yes = Fuzz.tokenExperiment(Clocks.Kind.HLC, true, 42);
    check("no_token_fast_writer_slow_reader_always_stale (" + no[0] + "/" + no[1] + ")", no[0] == no[1] && no[1] > 50);
    check("token_absorbed_via_receive_rule_never_stale   (" + yes[0] + "/" + yes[1] + ")", yes[0] == 0);
    int[] wallTok = Fuzz.tokenExperiment(Clocks.Kind.WALL, true, 42);
    check("wall_clock_cannot_honor_a_token               (" + wallTok[0] + "/" + wallTok[1] + " still stale)", wallTok[0] > 0);
    Core.Stamp x = new Core.Stamp(1724900000123L, 41);
    check("pack_48_plus_16_bits_roundtrips", Clocks.unpack(Clocks.pack(x)).equals(x));
    boolean threw = false;
    try { Clocks.pack(new Core.Stamp(5, 70000)); } catch (IllegalStateException e) { threw = true; }
    check("pack_counter_overflow_fails_loudly", threw);
  }

  // ---------------- planted bugs ----------------
  static void brokenSuite() {
    System.out.println("Each planted bug, and the guarantee it breaks:\n");
    {
      Clocks.Bugs b = new Clocks.Bugs(); b.neverResetC = true;
      Fuzz.Stats s = Fuzz.run(Fuzz.Config.of(Clocks.Kind.HLC, 42).bugged(b));
      Fuzz.Stats h = Fuzz.run(Fuzz.Config.of(Clocks.Kind.HLC, 42));
      System.out.println("neverResetC:        maxC " + h.maxC + " -> " + s.maxC
          + "  (c now counts EVERY event ever; the 16-bit budget that lasts forever at c<"
          + h.maxC + " dies in milliseconds at production rates)");
    }
    {
      Clocks.Bugs b = new Clocks.Bugs(); b.recvIgnoresMsgC = true;
      Fuzz.Stats s = Fuzz.run(Fuzz.Config.of(Clocks.Kind.HLC, 42).bugged(b));
      System.out.println("recvIgnoresMsgC:    " + s.inversions
          + " happens-before INVERSIONS  (a receive can be stamped below its own send when l ties — causality broken)");
    }
    {
      Clocks.Bugs b = new Clocks.Bugs(); b.recvIgnoresPt = true;
      Fuzz.Stats s = Fuzz.run(Fuzz.Config.of(Clocks.Kind.HLC, 42).bugged(b));
      System.out.println("recvIgnoresPt:      minDrift " + s.minDrift
          + "  (l falls BEHIND this node's own wall clock; 'snapshot at 5 p.m.' can now miss local events at 5 p.m.)");
    }
    {
      Clocks.Bugs b = new Clocks.Bugs(); b.compareIgnoresC = true;
      Fuzz.Stats s = Fuzz.run(Fuzz.Config.of(Clocks.Kind.HLC, 42).bugged(b));
      System.out.println("compareIgnoresC:    " + s.ties
          + " unorderable hb pairs  (everything the wall part cannot separate collapses back into ties — c IS the tiebreak)");
    }
    System.out.println("\n(The healthy suite: java src/Tests.java)");
  }
}
