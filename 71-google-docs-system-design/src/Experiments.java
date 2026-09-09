import java.util.*;

/**
 * Break it on purpose and watch.
 *
 * Run everything:      java src/Experiments.java
 * Run one experiment:  java src/Experiments.java lost-ack
 *
 * Experiments: raw-vs-ot, lost-request, lost-ack, out-of-order,
 *              offline-burst, stale-server, slow-client
 */
public final class Experiments {

    public static void main(String[] args) {
        Set<String> want = args.length == 0 ? null : new LinkedHashSet<>(Arrays.asList(args));
        if (want == null || want.contains("two-in-flight")) twoInFlight();
        if (want == null || want.contains("raw-vs-ot"))     rawVsOt();
        if (want == null || want.contains("lost-request"))  lostRequest();
        if (want == null || want.contains("lost-ack"))      lostAck();
        if (want == null || want.contains("out-of-order"))  outOfOrder();
        if (want == null || want.contains("offline-burst")) offlineBurst();
        if (want == null || want.contains("stale-server"))  staleServer();
        if (want == null || want.contains("slow-client"))   slowClient();
    }

    // ------------------------------------------------------------------ E0
    /**
     * Why a client may only have ONE edit in flight at a time.
     * We bypass the Client class here and post two edits to the server by hand,
     * both stamped with the revision the client was at when it started typing.
     */
    static void twoInFlight() {
        banner("E0  two-in-flight — the same client sends two edits without waiting");
        Collab.Server server = new Collab.Server("HAT", Collab.Mode.OT);
        Ot.Op first  = Ot.Op.insert(3, 'S', "Alice");   // Alice types S -> her screen "HATS"
        Ot.Op second = Ot.Op.insert(4, '!', "Alice");   // then ! -> her screen "HATS!"
        System.out.println("   Alice's screen: \"HATS!\"");
        System.out.println("   She sends both edits, and both say 'written against revision 0',");
        System.out.println("   because no confirmation has come back to move her revision on.");
        Ot.Op a = server.receive("Alice", 1, first, 0).op();
        System.out.println("   server applied " + a + "  -> \"" + server.doc + "\"  (revision 1)");
        try {
            Ot.Op b = server.receive("Alice", 2, second, 0).op();
            System.out.println("   server applied " + b + "  -> \"" + server.doc + "\"");
        } catch (RuntimeException e) {
            System.out.println("   server THREW: " + e.getMessage());
        }
        System.out.println("   The second edit was written on a screen that ALREADY contained the first one,");
        System.out.println("   but it claims to be based on revision 0, so the server rewrote it against the");
        System.out.println("   first edit a second time and pushed it one place too far.");
        System.out.println("   Hence the rule: one edit in flight, everything else waits in a queue.\n");
    }

    // ------------------------------------------------------------------ E1
    /** How often does the naive design actually break? Not "sometimes" — measure it. */
    static void rawVsOt() {
        banner("E1  raw-vs-ot — how often does skipping transformation diverge?");
        for (Collab.Mode mode : Collab.Mode.values()) {
            int diverged = 0, crashed = 0, runs = 500;
            for (int seed = 0; seed < runs; seed++) {
                Random rnd = new Random(seed);
                Collab.Sim s = new Collab.Sim("HAT", mode, "Alice", "Bob", "Carol");
                String[] sites = {"Alice", "Bob", "Carol"};
                try {
                    for (int step = 0; step < 20; step++) {
                        String site = sites[rnd.nextInt(3)];
                        Collab.Client cl = s.c(site);
                        if (rnd.nextInt(100) < 30 && cl.doc.length() > 1) s.erase(site, rnd.nextInt(cl.doc.length()));
                        else s.type(site, rnd.nextInt(cl.doc.length() + 1), (char) ('a' + rnd.nextInt(4)));
                        for (int k = rnd.nextInt(3); k > 0; k--) { int i = s.nextDeliverable(); if (i >= 0) s.deliverAt(i); }
                    }
                    s.settleRandom(rnd);
                    if (!s.converged()) diverged++;
                } catch (RuntimeException e) { crashed++; }
            }
            System.out.printf("   %-4s : %d of %d runs ended with the screens showing different text%s%n",
                    mode, diverged, runs, crashed > 0 ? " (" + crashed + " also threw)" : "");
        }
        System.out.println("   3 people, 20 edits each run, random delivery order.");
        System.out.println("   The naive design does not fail 'rarely under load'. It fails almost always.\n");
    }

    // ------------------------------------------------------------------ E2
    /** The network eats the edit on its way to the server. */
    static void lostRequest() {
        banner("E2  lost-request — Alice's keystroke never reaches the server");
        Collab.Sim s = new Collab.Sim("HAT", Collab.Mode.OT, "Alice", "Bob");
        s.type("Alice", 0, 'C');
        s.dropAt(s.find("Alice -> server"));
        s.type("Alice", 1, 'H');            // she keeps typing; this waits in her buffer
        s.type("Bob", 3, 'S');
        s.settle();
        s.printEvents();
        System.out.println("\n   " + s.docs());
        System.out.println("   Alice pending edits: " + s.c("Alice").pendingCount() + "  (she is stuck waiting for a confirmation that will never come)");
        System.out.println("   Nothing is corrupted — she is simply frozen. The fix is a timeout and a retry:");
        int mark = s.events.size();
        s.resend("Alice");
        s.settle();
        s.printNewEvents(mark);
        System.out.println("\n   " + s.docs() + "   converged=" + s.converged() + "\n");
    }

    // ------------------------------------------------------------------ E3
    /** The edit arrived. The CONFIRMATION is what got eaten. Now retrying is dangerous. */
    static void lostAck() {
        banner("E3  lost-ack — the edit landed, the confirmation did not, so Alice retries");
        for (boolean dedup : new boolean[]{false, true}) {
            Collab.Sim s = new Collab.Sim("HAT", Collab.Mode.OT, "Alice", "Bob");
            s.server.dedup = dedup;
            s.type("Alice", 0, 'C');
            s.deliverOne();                                  // server applies it
            s.dropAt(s.find("server -> Alice"));             // her confirmation is eaten
            s.settle();
            s.resend("Alice");                               // timeout fires, she retries
            s.settle();
            System.out.println("   duplicate suppression " + (dedup ? "ON " : "OFF") + " : " + s.docs()
                    + "   converged=" + s.converged());
            if (!dedup) System.out.println("        the C was inserted twice — a retry silently doubled her keystroke");
            else        System.out.println("        the server saw edit #1 from Alice twice, applied it once, re-sent the same confirmation");
        }
        System.out.println("\n   Retries are only safe if the server can tell 'this again' from 'another one'.");
        System.out.println("   Here that is a per-client edit number; in a payment API it is an idempotency key. Same idea.\n");
    }

    // ------------------------------------------------------------------ E4
    /** What if broadcasts reach a client in the wrong order? */
    static void outOfOrder() {
        banner("E4  out-of-order — two edits reach Bob in the wrong order");
        Collab.Sim s = new Collab.Sim("HAT", Collab.Mode.OT, "Alice", "Bob");
        s.disconnect("Bob");                 // hold Bob's messages so we can choose their order
        s.type("Alice", 0, 'C'); s.settle(); // server revision 1: insert C at 0  -> "CHAT"
        s.type("Alice", 4, 'S'); s.settle(); // server revision 2: insert S at 4  -> "CHATS"
        s.reconnect("Bob");

        List<Integer> forBob = new ArrayList<>();
        for (int i = 0; i < s.inFlight.size(); i++) if (s.inFlight.get(i).toString().startsWith("server -> Bob")) forBob.add(i);
        System.out.println("   messages waiting for Bob, in log order:");
        for (int i : forBob) System.out.println("      " + s.inFlight.get(i));
        System.out.println("   delivering them BACKWARDS (revision 2 before revision 1):");
        try {
            for (int k = forBob.size() - 1; k >= 0; k--) s.deliverAt(forBob.get(k));
            s.settle();
            System.out.println("   " + s.docs() + "   converged=" + s.converged());
        } catch (RuntimeException e) {
            System.out.println("      Bob's editor threw: " + e.getMessage());
        }
        System.out.println("   Bob was told to put S at position 4 of a document that is 3 characters long,");
        System.out.println("   because the edit that made room for it had not arrived yet.");
        System.out.println("   Transformation assumes every client sees the log in log order. That assumption is");
        System.out.println("   bought by using ONE ordered connection per client (a WebSocket over TCP) — not by luck.\n");
    }

    // ------------------------------------------------------------------ E5
    /** How expensive is coming back after a long time away? */
    static void offlineBurst() {
        banner("E5  offline-burst — Bob writes on a plane while 200 edits happen without him");
        Collab.Sim s = new Collab.Sim("HAT", Collab.Mode.OT, "Alice", "Bob");
        s.disconnect("Bob");
        for (int i = 0; i < 200; i++) { s.type("Alice", 0, 'a'); s.settle(); }
        for (int i = 0; i < 20; i++) s.type("Bob", 0, 'b');
        long before = Ot.transformCount;
        s.reconnect("Bob");
        s.settle();
        long work = Ot.transformCount - before;
        System.out.println("   server log length      : " + s.server.log.size());
        System.out.println("   Bob's offline edits    : 20");
        System.out.println("   transformations to heal: " + work);
        System.out.println("   converged              : " + s.converged() + ", document length " + s.server.doc.length());
        System.out.println("   Cost is roughly (missed edits x your own edits). Come back after a million edits");
        System.out.println("   and this is why real systems re-load the document instead of replaying the log.\n");
    }

    // ------------------------------------------------------------------ E6
    /** The server confirmed edits it had not yet written down, then restarted. */
    static void staleServer() {
        banner("E6  stale-server — the server acknowledged 2 edits it had not persisted, then restarted");
        Collab.Sim s = new Collab.Sim("HAT", Collab.Mode.OT, "Alice", "Bob");
        s.type("Alice", 0, 'C'); s.settle();
        s.type("Bob", 4, 'S');   s.settle();
        s.type("Alice", 5, '!'); s.settle();
        s.type("Bob", 6, '?');   s.settle();
        System.out.println("   before the crash: " + s.docs() + "  revision " + s.server.revision());

        // Restart from a snapshot that is missing the last two log entries.
        List<Ot.Op> survived = new ArrayList<>(s.server.log.subList(0, 2));
        s.server.log.clear(); s.server.log.addAll(survived);
        String rebuilt = "HAT";
        for (Ot.Op op : survived) rebuilt = Ot.apply(rebuilt, op);
        s.server.doc = rebuilt;
        System.out.println("   after the restart: server=\"" + s.server.doc + "\" revision " + s.server.revision()
                + " but Alice still believes she is at revision " + s.c("Alice").revision);

        try {
            s.type("Alice", 1, 'X');
            s.settle();
            System.out.println("   " + s.docs() + "   converged=" + s.converged());
            System.out.println("   The text on the screens and the text on the server no longer agree,");
        } catch (RuntimeException e) {
            System.out.println("   THREW: " + e.getMessage());
        }
        System.out.println("   and no client was told anything was wrong.");
        System.out.println("   Rule: never confirm an edit you have not durably written down.\n");
    }

    // ------------------------------------------------------------------ E7
    /** One tab on a bad connection. Who pays for it? */
    static void slowClient() {
        banner("E7  slow-client — Carol's connection stalls while everyone else keeps typing");
        Collab.Sim s = new Collab.Sim("HAT", Collab.Mode.OT, "Alice", "Bob", "Carol");
        s.disconnect("Carol");
        for (int i = 0; i < 100; i++) { s.type(i % 2 == 0 ? "Alice" : "Bob", 0, 'x'); s.settle(); }
        long queued = s.inFlight.stream().filter(m -> m.toString().startsWith("server -> Carol")).count();
        System.out.println("   edits made while Carol was stalled : 100");
        System.out.println("   messages queued for Carol alone    : " + queued);
        System.out.println("   Carol's screen                     : \"" + s.c("Carol").doc + "\" (still the original)");
        System.out.println("   Everyone else is fine — but the server is holding Carol's backlog in memory.");
        System.out.println("   With 200 people in one document, one bad connection each is 200 backlogs.");
        System.out.println("   Real systems cap the queue and force a full reload instead. Watch it heal:");
        s.reconnect("Carol");
        s.settle();
        System.out.println("   after reconnect: converged=" + s.converged() + ", document length " + s.server.doc.length() + "\n");
    }

    static void banner(String s) {
        System.out.println("\n===========================================================================");
        System.out.println(" " + s);
        System.out.println("===========================================================================");
    }
}
