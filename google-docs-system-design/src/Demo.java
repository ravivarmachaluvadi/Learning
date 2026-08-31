/**
 * The whole lesson in one run: the same two edits, under three designs.
 *
 *   The document says   HAT
 *   Alice wants         CHAT   (insert 'C' at position 0)
 *   Bob wants           HATS   (insert 'S' at position 3)
 *   Everyone should end at     CHATS
 *
 * Run:  java src/Demo.java
 */
public final class Demo {

    public static void main(String[] args) {
        lastWriterWins();
        rawOperations();
        operationalTransformation();
        theOtherDeliveryOrder();
    }

    // ---------------------------------------------------------------- design 1
    /** "Send the whole document when you save." This is how a shared file works. */
    static void lastWriterWins() {
        banner("DESIGN 1 — send the whole document, last save wins");
        String serverDoc = "HAT";
        String alice = serverDoc, bob = serverDoc;
        System.out.println("   everybody starts at \"" + serverDoc + "\"");

        alice = "C" + alice;          // Alice types C at the front
        System.out.println("   Alice types 'C'          her copy: \"" + alice + "\"");
        bob = bob + "S";              // Bob types S at the end
        System.out.println("   Bob types 'S'            his copy: \"" + bob + "\"");

        serverDoc = alice; System.out.println("   Alice saves              server: \"" + serverDoc + "\"");
        serverDoc = bob;   System.out.println("   Bob saves                server: \"" + serverDoc + "\"");

        System.out.println("\n   Final document: \"" + serverDoc + "\"");
        System.out.println("   Alice's 'C' is GONE. Nothing crashed, nothing warned her.");
        System.out.println("   Verdict: no divergence, but silent data loss. -> we must send CHANGES, not whole documents.\n");
    }

    // ---------------------------------------------------------------- design 2
    /** "Send the change: what, and where." Reasonable. Also wrong. */
    static void rawOperations() {
        banner("DESIGN 2 — send the change (position + character), apply it as sent");
        Collab.Sim sim = new Collab.Sim("HAT", Collab.Mode.RAW, "Alice", "Bob");
        sim.type("Alice", 0, 'C');
        sim.type("Bob", 3, 'S');
        sim.settle();
        sim.printEvents();
        System.out.println("\n   " + sim.docs());
        System.out.println("   Bob's edit said \"put S at position 3\". By the time Alice ran it,");
        System.out.println("   position 3 was no longer the end of the word — Alice's C had pushed everything right.");
        System.out.println("   Verdict: the two screens now show DIFFERENT text. This is divergence.\n");
    }

    // ---------------------------------------------------------------- design 3
    /** The same two edits, with every op rewritten against what it did not know about. */
    static void operationalTransformation() {
        banner("DESIGN 3 — same changes, but rewrite each one against the edits it never saw");
        Collab.Sim sim = new Collab.Sim("HAT", Collab.Mode.OT, "Alice", "Bob");
        sim.type("Alice", 0, 'C');
        sim.type("Bob", 3, 'S');
        sim.settle();
        sim.printEvents();
        System.out.println("\n   " + sim.docs());
        System.out.println("   converged = " + sim.converged());
        System.out.println("   Bob's \"insert S at 3\" became \"insert S at 4\" because one character");
        System.out.println("   was inserted before it. Both intentions survived: C at the front, S at the end.\n");
    }

    // ---------------------------------------------------------------- design 3b
    /**
     * The same two edits, but Bob's reaches the server first. Now the server
     * cannot fix anything up for Alice — when it rewrote Bob's edit it had never
     * heard of hers. Her own machine has to finish the job.
     */
    static void theOtherDeliveryOrder() {
        banner("DESIGN 3, other delivery order — why the CLIENT transforms too");
        Collab.Sim sim = new Collab.Sim("HAT", Collab.Mode.OT, "Alice", "Bob");
        sim.type("Alice", 0, 'C');
        sim.type("Bob", 3, 'S');
        sim.deliverAt(sim.find("Bob -> server"));   // Bob's edit overtakes Alice's
        sim.settle();
        sim.printEvents();
        System.out.println("\n   " + sim.docs());
        System.out.println("   converged = " + sim.converged());
        System.out.println("   The server logged Bob's edit unchanged, as revision 1 — correct, because at");
        System.out.println("   that moment Alice's C did not exist anywhere the server could see it.");
        System.out.println("   Alice's screen already showed \"CHAT\". Applying \"insert S at 3\" to it would give");
        System.out.println("   \"CHAST\". Her own editor rewrote it to \"insert S at 4\" against her own");
        System.out.println("   un-confirmed C. Transformation happens at BOTH ends, for different reasons.\n");
    }

    static void banner(String s) {
        System.out.println("\n===========================================================================");
        System.out.println(" " + s);
        System.out.println("===========================================================================");
    }
}
