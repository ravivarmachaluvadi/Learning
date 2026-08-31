import java.util.*;

/**
 * Executable documentation. Each test is a scenario you could describe to a
 * colleague out loud, and its name is that description.
 *
 * Run:  java src/Tests.java
 */
public final class Tests {

    static int passed = 0, failed = 0;

    public static void main(String[] args) {
        lww_silently_loses_an_edit();
        raw_operations_diverge_when_two_people_type_at_once();
        ot_converges_and_keeps_both_intentions();
        ot_insert_next_to_a_delete();
        ot_two_people_delete_the_same_character();
        ot_same_position_inserts_land_in_the_same_order_everywhere();
        ot_typing_a_burst_faster_than_the_round_trip();
        ot_client_edits_while_offline_then_reconnects();
        ot_late_joiner_catches_up();
        ot_converges_under_1000_random_schedules();

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------------

    static void lww_silently_loses_an_edit() {
        String start = "HAT";
        String alice = "C" + start, bob = start + "S";
        String server = alice;
        server = bob;                       // Bob saves second
        check("lww_silently_loses_an_edit",
              server.equals("HATS") && !server.contains("C"),
              "server=\"" + server + "\" — Alice's C was overwritten, no error raised");
    }

    static void raw_operations_diverge_when_two_people_type_at_once() {
        Collab.Sim s = new Collab.Sim("HAT", Collab.Mode.RAW, "Alice", "Bob");
        s.type("Alice", 0, 'C');
        s.type("Bob", 3, 'S');
        s.settle();
        check("raw_operations_diverge_when_two_people_type_at_once",
              !s.converged() && s.c("Alice").doc.equals("CHAST") && s.c("Bob").doc.equals("CHATS"),
              s.docs());
    }

    static void ot_converges_and_keeps_both_intentions() {
        Collab.Sim s = new Collab.Sim("HAT", Collab.Mode.OT, "Alice", "Bob");
        s.type("Alice", 0, 'C');
        s.type("Bob", 3, 'S');
        s.settle();
        check("ot_converges_and_keeps_both_intentions",
              s.converged() && s.server.doc.equals("CHATS"),
              s.docs() + "  (C at the front AND S at the end — both survived)");
    }

    static void ot_insert_next_to_a_delete() {
        // "CHATS": Alice deletes the S (position 4) while Bob types '!' after it.
        Collab.Sim s = new Collab.Sim("CHATS", Collab.Mode.OT, "Alice", "Bob");
        s.erase("Alice", 4);
        s.type("Bob", 5, '!');
        s.settle();
        check("ot_insert_next_to_a_delete",
              s.converged() && s.server.doc.equals("CHAT!"),
              s.docs());
    }

    static void ot_two_people_delete_the_same_character() {
        // Both delete the T at position 3 of "CHATS". One of the deletes must
        // become a no-op, or a bystander character gets eaten.
        Collab.Sim s = new Collab.Sim("CHATS", Collab.Mode.OT, "Alice", "Bob");
        s.erase("Alice", 3);
        s.erase("Bob", 3);
        s.settle();
        check("ot_two_people_delete_the_same_character",
              s.converged() && s.server.doc.equals("CHAS"),
              s.docs() + "  (one T removed, the S survived)");
    }

    static void ot_same_position_inserts_land_in_the_same_order_everywhere() {
        // Both type a different letter at exactly position 3 of "HAT".
        // There is no "right" order — but there must be ONE order.
        Collab.Sim s = new Collab.Sim("HAT", Collab.Mode.OT, "Alice", "Bob");
        s.type("Alice", 3, 'S');
        s.type("Bob", 3, '!');
        s.settle();
        check("ot_same_position_inserts_land_in_the_same_order_everywhere",
              s.converged() && s.server.doc.equals("HATS!"),
              s.docs() + "  (Alice < Bob by site id, so S goes first — arbitrary, but identical on every screen)");
    }

    static void ot_typing_a_burst_faster_than_the_round_trip() {
        // Alice types four characters before a single confirmation comes back.
        // Only the first is in flight; the rest wait in her buffer.
        Collab.Sim s = new Collab.Sim("HAT", Collab.Mode.OT, "Alice", "Bob");
        s.type("Alice", 3, 'S'); s.type("Alice", 4, ' '); s.type("Alice", 5, 'O'); s.type("Alice", 6, 'N');
        boolean oneInFlight = s.c("Alice").pendingCount() == 4;
        s.type("Bob", 0, 'C');
        s.settle();
        check("ot_typing_a_burst_faster_than_the_round_trip",
              oneInFlight && s.converged() && s.server.doc.equals("CHATS ON"),
              s.docs());
    }

    static void ot_client_edits_while_offline_then_reconnects() {
        Collab.Sim s = new Collab.Sim("HAT", Collab.Mode.OT, "Alice", "Bob");
        s.disconnect("Bob");
        s.type("Bob", 3, 'S');            // Bob keeps typing on a plane
        s.type("Bob", 4, '!');
        s.type("Alice", 0, 'C');          // meanwhile Alice edits online
        s.settle();                       // Alice's edit lands; Bob hears nothing
        boolean bobIsBehind = s.c("Bob").doc.equals("HATS!") && s.server.doc.equals("CHAT");
        s.reconnect("Bob");
        s.settle();
        check("ot_client_edits_while_offline_then_reconnects",
              bobIsBehind && s.converged() && s.server.doc.equals("CHATS!"),
              s.docs());
    }

    static void ot_late_joiner_catches_up() {
        Collab.Sim s = new Collab.Sim("HAT", Collab.Mode.OT, "Alice");
        s.type("Alice", 0, 'C');
        s.settle();
        s.join("Carol");                  // Carol opens the doc now
        s.type("Carol", 4, 'S');
        s.type("Alice", 0, 'A');
        s.settle();
        check("ot_late_joiner_catches_up",
              s.converged() && s.server.doc.equals("ACHATS"),
              s.docs());
    }

    static void ot_converges_under_1000_random_schedules() {
        int diverged = 0;
        for (int seed = 0; seed < 1000; seed++) {
            Random rnd = new Random(seed);
            Collab.Sim s = new Collab.Sim("HAT", Collab.Mode.OT, "Alice", "Bob", "Carol");
            String[] sites = {"Alice", "Bob", "Carol"};
            for (int step = 0; step < 30; step++) {
                String site = sites[rnd.nextInt(3)];
                Collab.Client cl = s.c(site);
                if (rnd.nextInt(100) < 30 && cl.doc.length() > 1) s.erase(site, rnd.nextInt(cl.doc.length()));
                else s.type(site, rnd.nextInt(cl.doc.length() + 1), (char) ('a' + rnd.nextInt(4)));
                // deliver a random subset now, leave the rest in flight
                for (int k = rnd.nextInt(3); k > 0; k--) {
                    int i = s.nextDeliverable();
                    if (i >= 0) s.deliverAt(i);
                }
            }
            s.settleRandom(rnd);
            if (!s.converged()) diverged++;
        }
        check("ot_converges_under_1000_random_schedules", diverged == 0,
              "3 clients x 30 edits x 1000 different delivery schedules, diverged " + diverged + " times");
    }

    // ------------------------------------------------------------------------

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name + "\n        " + detail);
        if (ok) passed++; else failed++;
    }
}
