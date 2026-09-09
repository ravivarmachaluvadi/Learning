import java.util.*;

/**
 * One narrated afternoon. Amira is on the London Underground, Ben is on a
 * plane to Berlin. Neither has signal. Both are editing the same shopping list
 * in the same app, and both expect their edits to be there when they land.
 *
 *   java src/Sim.java
 */
public class Sim {

    public static void main(String[] args) {
        scene1_theNaiveApp();
        scene2_theScoreboard();
        scene3_theSharedList();
        scene4_theNetworkMisbehaves();
        scene5_theThingItCannotDo();
    }

    // ------------------------------------------------------------------- 1

    static void scene1_theNaiveApp() {
        title("SCENE 1 — the app they have today");
        say("Both phones start with the same list: [bread].");
        say("Amira adds milk. Ben adds eggs. Each phone sends its WHOLE list up,");
        say("and the server keeps whichever arrived last.");

        LwwRegister<String> amira = new LwwRegister<String>().assign("[bread, milk]", 10_000, "amira");
        LwwRegister<String> ben   = new LwwRegister<String>().assign("[bread, eggs]", 10_050, "ben");
        LwwRegister<String> server = amira.merge(ben);

        show("Amira's phone sent", amira.value());
        show("Ben's phone sent", ben.value());
        show("what the server now holds", server.value());
        say("");
        say("Amira's milk is gone. Nobody was told. No error, no conflict screen —");
        say("the app is working exactly as designed, and the design is the bug.");
    }

    // ------------------------------------------------------------------- 2

    static void scene2_theScoreboard() {
        title("SCENE 2 — the first fix: stop shipping the answer, ship the evidence");
        say("Start with the easy case: a number. The list has a 'times shared' count.");
        say("Instead of one number, each phone keeps a row per phone and may only");
        say("ever raise its OWN row. Merging means taking the bigger of each row.");

        GCounter amira = new GCounter().increment("amira", 1);
        GCounter ben   = new GCounter().increment("ben", 1);
        show("Amira's scoreboard", amira.rows() + "  -> she reads " + amira.value());
        show("Ben's scoreboard",   ben.rows()   + "  -> he reads "  + ben.value());
        GCounter merged = amira.merge(ben);
        show("after they sync",    merged.rows() + "  -> both read " + merged.value());

        say("");
        say("Now the mischief. The sync message is delivered three more times,");
        say("because the phone retried on a flaky connection.");
        GCounter spammed = merged.merge(amira).merge(amira).merge(amira);
        show("after 3 duplicate deliveries", spammed.rows() + "  -> still " + spammed.value());
        say("Taking the maximum of a row you already have changes nothing. That is");
        say("what makes duplicate delivery stop being a problem you have to solve.");
    }

    // ------------------------------------------------------------------- 3

    static void scene3_theSharedList() {
        title("SCENE 3 — the list itself: adds, removes, and a change of mind");
        say("Every add mints a unique receipt: (whose phone, which action). A remove");
        say("tears up the receipts it can actually see. Nothing else.");

        OrSet<String> shared = new OrSet<String>()
                .add("bread", new Dot("amira", 1));
        OrSet<String> amira = shared.add("milk", new Dot("amira", 2));
        OrSet<String> ben   = shared.add("eggs", new Dot("ben", 1));

        show("Amira, underground", amira.value().toString());
        show("Ben, over the North Sea", ben.value().toString());
        OrSet<String> together = amira.merge(ben);
        show("both land, phones sync", together.value().toString());
        say("Both edits survived. No server had to decide anything.");

        say("");
        say("Ben deletes bread. Amira, still offline, deletes bread too.");
        OrSet<String> benDel = together.remove("bread");
        OrSet<String> amiraDel = together.remove("bread");
        show("after they sync", benDel.merge(amiraDel).value().toString());
        say("Two people deleting the same thing is not a conflict — it tears up the");
        say("same receipt twice, and tearing up a torn receipt does nothing.");

        say("");
        say("Now the awkward one. Ben deletes milk. At the same moment Amira, with");
        say("no signal, adds milk again because she is standing in the dairy aisle.");
        OrSet<String> benRemovesMilk = together.remove("milk");
        OrSet<String> amiraReadds    = together.add("milk", new Dot("amira", 9));
        OrSet<String> outcome = benRemovesMilk.merge(amiraReadds);
        show("result", outcome.value().toString());
        say("Milk stays. Ben's delete only names receipt amira#2, and Amira's new");
        say("receipt is amira#9 — a receipt Ben has never seen and therefore cannot");
        say("have meant to destroy. 'Add wins' is not a coin toss; it is a rule.");
    }

    // ------------------------------------------------------------------- 4

    static void scene4_theNetworkMisbehaves() {
        title("SCENE 4 — the network does its worst");
        say("Four phones. Half the sync messages are dropped, half are delivered");
        say("twice, all of them arrive in random order, and for a while the two");
        say("pairs cannot reach each other at all.");

        Random rnd = new Random(9);
        Cluster<OrSet<String>> c = new Cluster<>(rnd);
        c.dropRate = 0.5; c.duplicateRate = 0.5; c.shuffle = true;
        List<String> phones = List.of("amira", "ben", "cara", "dev");
        for (String p : phones) c.addReplica(p, new OrSet<>());
        c.partition("amira", 0); c.partition("ben", 0);
        c.partition("cara", 1);  c.partition("dev", 1);

        String[] items = {"bread", "milk", "eggs", "jam", "tea", "rice", "salt", "figs"};
        long dot = 0;
        for (int step = 0; step < 24; step++) {
            String p = phones.get(rnd.nextInt(phones.size()));
            String item = items[rnd.nextInt(items.length)];
            long d = ++dot;
            c.update(p, s -> rnd.nextInt(4) == 0 && s.contains(item) ? s.remove(item) : s.add(item, new Dot(p, d)));
            c.gossipRound(); c.deliverAll();
        }

        say("");
        say("While the two pairs are cut off, they see different lists:");
        for (String p : phones) show("  " + p, c.state(p).value().toString());
        show("distinct lists in the cluster", String.valueOf(c.distinctViews(OrSet::value).size()));

        c.healPartition();
        int rounds = c.settle(40);
        say("");
        say("The partition heals. Nobody replays a log, nobody elects anyone,");
        say("nobody rolls anything back. They just sync as usual.");
        show("rounds needed to agree", String.valueOf(rounds));
        for (String p : phones) show("  " + p, c.state(p).value().toString());
        show("messages dropped / duplicated", c.dropped + " / " + c.duplicated);
    }

    // ------------------------------------------------------------------- 5

    static void scene5_theThingItCannotDo() {
        title("SCENE 5 — the sentence you must be able to say about CRDTs");
        say("The shop has 10 bags of flour. Two warehouses, partitioned, each sell 6.");
        say("Each one checked its own stock first, and each one was right.");

        PNCounter stock = new PNCounter().increment("shop", 10);
        PNCounter w1 = stock.decrement("w1", 6);
        PNCounter w2 = stock.decrement("w2", 6);
        show("warehouse 1 believes", String.valueOf(w1.value()));
        show("warehouse 2 believes", String.valueOf(w2.value()));
        show("after they sync", String.valueOf(w1.merge(w2).value()));
        say("");
        say("Minus two bags of flour. The merge is not wrong — it faithfully recorded");
        say("everything that happened. The system simply has no way to enforce a rule");
        say("about the TOTAL while the parts cannot talk.");
        say("");
        say("CRDTs guarantee that everyone ends up agreeing. They do not guarantee");
        say("that what everyone agrees on was ever allowed. Any invariant that spans");
        say("replicas — stock >= 0, unique username, balance >= 0 — needs the other");
        say("family of tools: a quorum, a leader, consensus. See the paxos and");
        say("quorums-and-replication labs for that side of the fence.");
    }

    // --------------------------------------------------------------- utils

    static void title(String s) {
        System.out.println("\n" + "=".repeat(74));
        System.out.println(s);
        System.out.println("=".repeat(74));
    }
    static void say(String s) { System.out.println("  " + s); }
    static void show(String label, String value) { System.out.printf("  %-32s %s%n", label + " :", value); }
}
