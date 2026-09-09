import java.util.*;

/**
 * One narrated afternoon in a three-region social app.
 *
 *   java src/Sim.java
 *
 * Amira is served from the London replica, Ben from Frankfurt, Cara from
 * Singapore. Nobody coordinates; every replica answers instantly and gossips
 * the change to the others. That is the design people actually ship, and the
 * first scene shows what it costs.
 */
public class Sim {

    public static void main(String[] args) {
        scene1_theCommentWithNoPost();
        scene2_theSameStoryWithCausalOrdering();
        scene3_thePrivacyVersion();
        scene4_whatCausalStillDoesNotGiveYou();
        scene5_theOneThingItBuysThatOrderingCannot();
    }

    // ------------------------------------------------------------------- 1

    static void scene1_theCommentWithNoPost() {
        title("SCENE 1 — the app as it is usually built: apply writes the moment they arrive");
        Cluster c = build(Rule.async(), 42);
        c.minDelay = 2; c.maxDelay = 2;
        c.linkDelay[0][2] = 10;   // London -> Singapore is crawling this afternoon

        say("Amira posts a photo. Her own replica shows it instantly.");
        c.put("amira", "post", "photo-of-a-dog");
        c.run(3);
        say("Ben, in Frankfurt, has now received the post and reads it.");
        show("Ben reads post", c.get("ben", "post"));
        say("Ben comments on the photo he just read.");
        c.put("ben", "comment", "lovely dog!");
        c.run(3);

        say("");
        say("Cara, in Singapore, opens the app. Ben's comment took the fast route;");
        say("Amira's post is still crawling across the slow link.");
        show("Cara reads comment", c.get("cara", "comment"));
        show("Cara reads post", String.valueOf(c.get("cara", "post")));
        System.out.print(c.describeReplicas());
        report(c);
        say("Nothing is broken, nothing errored, and every replica is doing exactly");
        say("what it was told. Cara is simply looking at an effect whose cause has");
        say("not arrived. That is the entire subject of this lab.");
    }

    // ------------------------------------------------------------------- 2

    static void scene2_theSameStoryWithCausalOrdering() {
        title("SCENE 2 — the same afternoon, with one rule added");
        say("The rule: a replica may not show you a write until it has already");
        say("applied everything that write was based on.");
        Cluster c = build(Rule.causalVv(), 42);
        c.minDelay = 2; c.maxDelay = 2;
        c.linkDelay[0][2] = 10;

        c.put("amira", "post", "photo-of-a-dog");
        c.run(3);
        c.get("ben", "post");
        c.put("ben", "comment", "lovely dog!");
        c.run(3);

        show("Cara reads comment", String.valueOf(c.get("cara", "comment")));
        show("Cara reads post", String.valueOf(c.get("cara", "post")));
        System.out.print(c.describeReplicas());
        report(c);
        say("Cara sees neither, instead of seeing the second without the first.");
        say("Read that again: the fix did not make anything arrive sooner. It made");
        say("the app WAIT rather than show a nonsense state. Consistency here is");
        say("bought entirely with latency.");

        int ticks = c.settle(50);
        say("");
        show("ticks until everything is delivered", String.valueOf(ticks));
        show("Cara reads post", String.valueOf(c.get("cara", "post")));
        show("Cara reads comment", String.valueOf(c.get("cara", "comment")));
    }

    // ------------------------------------------------------------------- 3

    static void scene3_thePrivacyVersion() {
        title("SCENE 3 — the same bug, with consequences");
        say("Amira removes her manager from the list of people who can see her");
        say("photos, and then posts a photo she does not want her manager to see.");
        say("Two writes, two different keys, one obvious order.");

        for (Rule rule : List.of(Rule.async(), Rule.causalVv())) {
            Cluster c = build(rule, 7);
            c.minDelay = 2; c.maxDelay = 2;
            // The two keys live on different shards, so their updates travel
            // independently — and today the audience shard is the slow one.
            c.overrideDelay = 12;
            c.put("amira", "audience", "everyone-except-manager");
            c.run(1);
            c.overrideDelay = 2;
            c.put("amira", "post", "the-holiday-photo");
            c.run(4);
            say("");
            say("  rule: " + rule.label());
            show("  manager's replica shows audience", String.valueOf(c.get("cara", "audience")));
            show("  manager's replica shows post", String.valueOf(c.get("cara", "post")));
            boolean leak = "the-holiday-photo".equals(c.view(2).get("post"))
                        && !"everyone-except-manager".equals(c.view(2).get("audience"));
            show("  photo visible under the OLD audience", leak ? "YES — this is the leak" : "no");
        }
        say("");
        say("This is the example from the COPS paper, and it is why causal");
        say("consistency is treated as a security property in social products,");
        say("not merely a tidiness one.");
    }

    // ------------------------------------------------------------------- 4

    static void scene4_whatCausalStillDoesNotGiveYou() {
        title("SCENE 4 — what the rule does NOT fix");
        say("Amira and Ben both set the same key at the same moment, each unaware");
        say("of the other. Neither write depends on the other, so causal ordering");
        say("has nothing to say about which comes first.");

        Cluster noConv = build(Rule.causalNoConverge(), 3);
        noConv.minDelay = 1; noConv.maxDelay = 4;
        noConv.put("amira", "title", "Dog Photos");
        noConv.put("ben", "title", "My Dog");
        noConv.settle(40);
        say("");
        say("  causal ordering only, no rule for concurrent writes:");
        for (int i = 0; i < 3; i++) show("    R" + i + " title", noConv.view(i).get("title"));
        show("  all replicas agree", String.valueOf(noConv.converged()));

        Cluster conv = build(Rule.causalVv(), 3);
        conv.minDelay = 1; conv.maxDelay = 4;
        conv.put("amira", "title", "Dog Photos");
        conv.put("ben", "title", "My Dog");
        conv.settle(40);
        say("");
        say("  the same thing plus one agreed tie-break (this is \"causal+\"):");
        for (int i = 0; i < 3; i++) show("    R" + i + " title", conv.view(i).get("title"));
        show("  all replicas agree", String.valueOf(conv.converged()));
        say("");
        say("Causal consistency orders what is ordered and says nothing about the");
        say("rest — so on its own it does not even promise the replicas end up the");
        say("same. Every real system pairs it with a convergence rule: a timestamp");
        say("tie-break like this one, or a CRDT merge (see the crdts lab).");
    }

    // ------------------------------------------------------------------- 5

    static void scene5_theOneThingItBuysThatOrderingCannot() {
        title("SCENE 5 — the reason to accept all of this: it keeps working when the network does not");
        say("The link between Europe and Singapore goes down completely.");

        Cluster c = build(Rule.causalVv(), 11);
        c.minDelay = 1; c.maxDelay = 2;
        c.put("amira", "post", "before-the-outage");
        c.settle(20);

        c.partition(0, 0); c.partition(1, 0); c.partition(2, 1);
        say("");
        say("During the outage — nobody blocks, nobody errors:");
        c.put("amira", "post", "written-in-london");
        c.put("cara", "note", "written-in-singapore");
        c.run(5);
        show("  Amira reads her own post", c.get("amira", "post"));
        show("  Cara reads her own note", c.get("cara", "note"));
        show("  Cara reads post", c.get("cara", "post"));
        say("  Cara's view is stale, and stale is a legal answer here. A");
        say("  linearizable store would have had to refuse her instead.");

        c.heal();
        say("");
        say("  The link comes back. Note that causal delivery does NOT resend what");
        say("  was lost during the outage — an anti-entropy round has to do that,");
        say("  exactly as in the gossip-protocols lab.");
        c.antiEntropy();
        int ticks = c.settle(50);
        say("");
        show("  ticks to catch up after healing", String.valueOf(ticks));
        show("  Cara reads post", c.get("cara", "post"));
        show("  all replicas agree", String.valueOf(c.converged()));
        report(c);
        say("Causal consistency is the strongest model that can do that — stay");
        say("available on both sides of a partition. Anything stronger has to");
        say("stop answering somewhere, which is the cap-theorem lab's whole point.");
    }

    // --------------------------------------------------------------- helpers

    static Cluster build(Rule rule, int seed) {
        Cluster c = new Cluster(3, rule, new Random(seed));
        c.client("amira", 0);
        c.client("ben", 1);
        c.client("cara", 2);
        return c;
    }

    static void report(Cluster c) {
        say("");
        say("  anomalies observed by clients: " + c.causalAnomalies);
        for (Cluster.Violation v : c.violations)
            say("    t=" + v.tick() + " " + v.client() + " [" + v.kind() + "] " + v.detail());
    }

    static void title(String s) {
        System.out.println("\n" + "=".repeat(78));
        System.out.println(s);
        System.out.println("=".repeat(78));
    }
    static void say(String s) { System.out.println("  " + s); }
    static void show(String label, String value) { System.out.printf("  %-38s %s%n", label + " :", value); }
}
