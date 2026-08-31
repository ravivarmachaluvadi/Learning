// Scenes.java — five narrated scenes. Watch the clocks tick, one event at a time.
//   java src/Scenes.java        -> all five
//   java src/Scenes.java 3      -> just scene 3
import java.util.*;

public class Scenes {
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

  // Two newsroom bureaus. Asha's newsroom clock runs 5 minutes FAST, Ben's 5 SLOW.
  static Core.World newsroom(Clocks.Kind k) {
    return new Core.World(2, new long[]{+5, -5}, Clocks.make(k, 2), 7);
  }

  static void scene1() {
    head("SCENE 1 — The correction that arrived before the article");
    Core.World w = newsroom(Clocks.Kind.WALL);
    say("Asha (bureau A, clock +5 fast) and Ben (bureau B, clock -5 slow) stamp every");
    say("action with their own wall clock. True time is shown as 'tick'.\n");
    w.tick = 10;
    Core.Event article = w.send(0, 1, 2);
    say("tick 10: Asha publishes an article and wires it to Ben. Her clock stamps it " + article.stamp + ".");
    w.tick = 12;
    Core.Event recv = w.deliverDue().get(0);
    say("tick 12: Ben receives it. HIS clock stamps the receipt " + recv.stamp + ".");
    say("");
    say("Read that again: the article was SENT at " + article.stamp + " and RECEIVED at " + recv.stamp + ".");
    say("Sort the archive by timestamp and the receipt precedes its own cause by "
        + (article.stamp.l() - recv.stamp.l()) + " ticks.");
    say("An effect filed before its cause — the fuzz counts " + 65 + " such inversions per run at skew 8");
    say("(experiment 1). The archive is not history; it is fiction.");
  }

  static void scene2() {
    head("SCENE 2 — Lamport fixes the order and burns the map");
    Core.World w = newsroom(Clocks.Kind.LAMPORT);
    w.tick = 10;
    Core.Event article = w.send(0, 1, 2);
    w.tick = 12;
    Core.Event recv = w.deliverDue().get(0);
    say("Same two actions, Lamport stamps (each event: counter = max(own, heard)+1):");
    say("tick 10: article sent, stamp " + article.stamp + ".   tick 12: received, stamp " + recv.stamp + ".");
    say("Cause before effect — always. (causal-consistency lab, remembered.)\n");
    say("Now Asha has a busy afternoon: 40 local edits.");
    w.tick = 13;
    Core.Event last = null;
    for (int i = 0; i < 40; i++) last = w.localEvent(0, "edit");
    say("tick 13: Asha's counter has raced to " + last.stamp + " while the wall says 13+5=18.");
    Core.Event ben = w.localEvent(1, "note");
    say("tick 13: Ben's counter is still at " + ben.stamp + ".");
    say("");
    say("Ask the archive 'what did the newsroom look like at counter 20?' — that moment is");
    say("mid-afternoon for Asha and next week for Ben. Ask 'at 5 p.m.?' — no answer exists:");
    say("the counter never heard of 5 p.m. Ordered, but timeless.");
  }

  static void scene3() {
    head("SCENE 3 — HLC, event by event: two numbers, two jobs");
    Core.World w = newsroom(Clocks.Kind.HLC);
    say("The stamp is (l, c): l may only ever be a wall reading someone actually took;");
    say("c counts the events l cannot tell apart. Watch both.\n");
    w.tick = 10;
    Core.Event e1 = w.localEvent(0, "edit");
    say("tick 10: Asha edits. Her wall reads 15 (fast clock). l adopts it: stamp " + e1.stamp + ".");
    Core.Event e2 = w.localEvent(0, "edit");
    Core.Event e3 = w.localEvent(0, "edit");
    say("tick 10, again and again: two more edits in the SAME tick. The wall still reads 15,");
    say("so l cannot move — c borrows the difference: " + e2.stamp + ", then " + e3.stamp + ".");
    w.tick = 11;
    Core.Event e4 = w.localEvent(0, "edit");
    say("tick 11: wall reads 16 > l. l advances, and c owes nothing: " + e4.stamp + ".");
    Core.Event s = w.send(0, 1, 2);
    say("tick 11: Asha wires Ben a story, stamped " + s.stamp + ".");
    w.tick = 13;
    Core.Event r = w.deliverDue().get(0);
    say("tick 13: Ben receives it. HIS wall reads only 8 — but the message says " + s.stamp + ".");
    say("         l = max(own 0, msg " + s.stamp.l() + ", wall 8) = " + r.stamp.l()
        + "; l tied with the message, so c = msg.c+1: stamp " + r.stamp + ".");
    Core.Event b = w.localEvent(1, "note");
    say("tick 13: Ben works. Wall still 8, l still " + b.stamp.l() + ": stamp " + b.stamp + ".");
    say("");
    say("Cause before effect at every arrow — and every l is a real wall reading, at most");
    say("the clock spread away from any node's own clock. Drift bounded, order exact.");
  }

  static void scene4() {
    head("SCENE 4 — 'The newsroom as of tick 40' — cutting history at a time");
    say("The editor wants a consistent snapshot at a WALL time: everything stamped <= T,");
    say("nothing else. Torn = a received story whose sending isn't in the snapshot.\n");
    for (Clocks.Kind k : new Clocks.Kind[]{Clocks.Kind.WALL, Clocks.Kind.HLC}) {
      Fuzz.Stats st = Fuzz.run(Fuzz.Config.of(k, 42));
      say(String.format("%-8s 200 candidate cut times: %d torn", k, st.cutViolations));
    }
    say("");
    say("The HLC cut is the distributed-transactions lab's 'consistent cut', finally");
    say("addressable by wall time — the exact capability CockroachDB reads at, and what");
    say("a Lamport counter could provide only at meaningless counter values.");
  }

  static void scene5() {
    head("SCENE 5 — The token: read your own write across two doors");
    say("A client writes through Asha's fast-clock node, then reads through Ben's slow one.");
    int[] no = Fuzz.tokenExperiment(Clocks.Kind.HLC, false, 42);
    say("Without carrying anything: " + no[0] + "/" + no[1] + " reads predate the write —");
    say("Ben's 'now' is simply earlier than the stamp Asha issued.");
    int[] yes = Fuzz.tokenExperiment(Clocks.Kind.HLC, true, 42);
    say("Now the client CARRIES the write's stamp and hands it to Ben, who absorbs it with");
    say("the ordinary receive rule before reading: " + yes[0] + "/" + yes[1] + " stale.");
    say("");
    say("No new machinery — the token IS a message without a payload. MongoDB ships this as");
    say("clusterTime piggybacked on every reply, honored via afterClusterTime (the session");
    say("token the causal-consistency lab called exercise 7.1 — this is its implementation).");
  }
}
