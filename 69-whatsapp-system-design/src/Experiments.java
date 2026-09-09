import java.util.List;
import java.util.Set;

/**
 * Break it on purpose and watch.
 *
 *     java src/Experiments.java            # list them
 *     java src/Experiments.java trace      # every packet of one happy message
 *     java src/Experiments.java offline crash partition dup group storm capacity
 */
public class Experiments {

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("""
                    experiments:
                      trace      one message, every packet, tick by tick
                      offline    recipient in a tunnel for a week -> mailbox growth -> catch-up
                      crash      kill the connection server holding the socket, mid-chat
                      partition  cut the cluster in half and see who can still talk
                      dup        force duplicate delivery and watch client dedup absorb it
                      group      fan-out cost: 1 typed message vs a 5- and a 256-member group
                      ordering   why a single ordered connection is the whole ordering story
                      storm      50,000 phones reconnect at the same second
                      capacity   the back-of-envelope numbers (arithmetic, not a simulation)
                    """);
            return;
        }
        for (String a : args) {
            System.out.println("\n########## " + a + " ##########");
            switch (a) {
                case "trace" -> trace();
                case "offline" -> offline();
                case "crash" -> crash();
                case "partition" -> partition();
                case "dup" -> dup();
                case "group" -> group();
                case "ordering" -> ordering();
                case "storm" -> storm();
                case "capacity" -> capacity();
                default -> System.out.println("unknown experiment: " + a);
            }
        }
    }

    // ------------------------------------------------------------------

    static void trace() {
        say("Asha types 'on my way'. Watch it become one grey tick, then two, then blue.");
        var c = pair(true);
        c.connectAll();
        c.net.runUntilIdle();
        System.out.println("--- send ---");
        Msg m = c.device("asha:phone").send("bilal", "asha-bilal", "on my way");
        c.net.runUntilIdle();
        System.out.println("--- bilal opens the chat ---");
        c.device("bilal:phone").read(m);
        c.net.runUntilIdle();
        System.out.println("\nasha's screen: " + c.device("asha:phone").tickOf(m.id()));
        System.out.println(c.stats());
    }

    static void offline() {
        say("Bilal's phone is off for a simulated week. Asha keeps typing.");
        var c = pair(false);
        c.device("asha:phone").connect(c.homeFor("asha:phone"));
        for (int i = 1; i <= 12; i++) c.device("asha:phone").send("bilal", "asha-bilal", "day msg " + i);
        c.net.runUntilIdle();
        System.out.println("bilal's mailbox depth while away : " + c.boxes.size("bilal:phone"));
        System.out.println("what bilal has seen              : " + c.device("bilal:phone").chatWindow.size());
        System.out.println("asha's ticks on message 1        : " + c.device("asha:phone").tickOf("asha:phone-asha-bilal-1"));
        System.out.println("\n>>> bilal comes back online <<<");
        c.net.verbose = true;
        c.device("bilal:phone").connect(c.homeFor("bilal:phone"));
        c.net.runUntilIdle();
        c.net.verbose = false;
        System.out.println("\nbilal now sees                   : " + c.device("bilal:phone").chatWindow.size() + " messages");
        System.out.println("mailbox depth after catch-up     : " + c.boxes.size("bilal:phone"));
        System.out.println("asha's ticks on message 1        : " + c.device("asha:phone").tickOf("asha:phone-asha-bilal-1"));
    }

    static void crash() {
        say("Mid-chat, the connection server holding Bilal's socket dies.");
        var c = pair(false);
        c.connectAll();
        c.net.runUntilIdle();
        String dead = c.registryServerOf("bilal:phone");
        System.out.println("bilal's socket lives on " + dead + "; the registry says so too.");
        c.server(dead).crash();
        System.out.println("registry still points at " + dead + ": " + c.registryServerOf("bilal:phone") + "   <-- a lie");

        c.net.verbose = true;
        c.device("asha:phone").send("bilal", "asha-bilal", "you there?");
        c.net.runUntilIdle();
        c.net.verbose = false;
        System.out.println("\nbilal saw            : " + c.device("bilal:phone").chatWindow.size() + " messages");
        System.out.println("safe in the mailbox  : " + c.boxes.size("bilal:phone"));
        System.out.println("asha's tick          : " + c.device("asha:phone").tickOf("asha:phone-asha-bilal-1")
                + "   <-- honest: the service has it, the device does not");

        System.out.println("\n>>> heartbeat expires, the stale row is purged, bilal reconnects elsewhere <<<");
        c.registry.purge(dead);
        c.net.revive(dead);
        c.device("bilal:phone").connect(c.homeFor("bilal:phone"));
        c.net.runUntilIdle();
        System.out.println("bilal now sees       : " + c.device("bilal:phone").chatWindow.size() + " messages");
        System.out.println("asha's tick          : " + c.device("asha:phone").tickOf("asha:phone-asha-bilal-1"));
    }

    static void partition() {
        say("The link between two halves of the cluster is cut. Asha is on one side, Bilal on the other.");
        var c = new Cluster(2, 42);
        c.addUser("asha", "phone");
        c.addUser("bilal", "phone");
        c.device("asha:phone").connect(c.server("cs1"));
        c.device("bilal:phone").connect(c.server("cs2"));
        c.net.runUntilIdle();
        c.net.partition(Set.of("cs1", "asha:phone"), Set.of("cs2", "bilal:phone"));
        System.out.println("network cut: {cs1, asha} | {cs2, bilal}");

        c.net.verbose = true;
        Msg m = c.device("asha:phone").send("bilal", "asha-bilal", "still there?");
        c.net.runFor(3000);
        c.net.verbose = false;
        System.out.println("\nasha's tick during the partition : " + c.device("asha:phone").tickOf(m.id()));
        System.out.println("bilal saw                        : " + c.device("bilal:phone").chatWindow.size());
        System.out.println("queued                           : " + c.boxes.totalUndelivered());
        System.out.println("packets lost to the partition    : " + c.net.partitionDropped);

        System.out.println("\n>>> partition heals <<<");
        c.net.healPartition();
        c.startSweepers(1000, c.net.now + 20_000);
        c.net.runUntilIdle();
        System.out.println("bilal now sees                   : " + c.device("bilal:phone").chatWindow.size());
        System.out.println("asha's tick                      : " + c.device("asha:phone").tickOf(m.id()));
    }

    static void dup() {
        say("Every packet is delivered twice. Does the human see double?");
        var c = pair(false);
        c.connectAll();
        c.net.runUntilIdle();
        c.net.dupRate = 1.0;
        for (int i = 0; i < 10; i++) c.device("asha:phone").send("bilal", "asha-bilal", "d" + i);
        c.net.runUntilIdle();
        var d = c.device("bilal:phone");
        System.out.println("messages typed by asha        : 10");
        System.out.println("copies that reached the device: " + (d.chatWindow.size() + d.duplicatesSuppressed));
        System.out.println("duplicates suppressed by id   : " + d.duplicatesSuppressed);
        System.out.println("messages shown to the human   : " + d.chatWindow.size());
        System.out.println("out-of-order arrivals noticed : " + d.outOfOrderSeen);
    }

    static void group() {
        say("Fan-out cost. One typed message; count the stored copies and the pushes.");
        for (int size : new int[]{5, 256}) {
            var c = new Cluster(8, 3);
            String[] members = new String[size];
            for (int i = 0; i < size; i++) { members[i] = "u" + i; c.addUser("u" + i, "phone"); }
            c.addGroup("#g", members);
            c.connectAll();
            c.net.runUntilIdle();
            long p0 = c.net.sent;
            c.device("u0:phone").send("#g", "g", "hello everyone");
            c.net.runUntilIdle();
            System.out.printf("group of %3d : 1 typed message -> %3d mailbox writes, %4d network packets%n",
                    size, c.boxes.appends, c.net.sent - p0);
        }
        System.out.println("\nThe write amplification is linear in group size, and it is paid on EVERY message.");
    }

    static void ordering() {
        say("Asha sends 12 messages while Bilal is offline. He reconnects and they all arrive at once.");
        for (boolean ordered : new boolean[]{true, false}) {
            var c = pair(false);
            c.net.orderedLinks = ordered;
            c.device("asha:phone").connect(c.homeFor("asha:phone"));
            for (int i = 1; i <= 12; i++) c.device("asha:phone").send("bilal", "asha-bilal", "line " + i);
            c.net.runUntilIdle();
            c.device("bilal:phone").connect(c.homeFor("bilal:phone"));
            c.net.runUntilIdle();
            var d = c.device("bilal:phone");
            System.out.println((ordered ? "one ordered connection (real TCP): " : "packets free to overtake       : ")
                    + "out-of-order arrivals = " + d.outOfOrderSeen
                    + ", screen order = " + d.chatWindow.stream().map(m -> String.valueOf(m.seq())).toList());
        }
        System.out.println("""
                
                Nothing in the servers sorts anything. Per-conversation order is a
                free gift from delivering over ONE ordered connection, in the order
                the mailbox stored them. Split a conversation across two connections
                and the ordering guarantee is gone -- which is exactly why the design
                pins a device to one socket instead of load-balancing its packets.""");
    }

    static void storm() {
        say("50,000 phones reconnect in the same second (a data-centre failover).");
        int devices = 50_000, servers = 20;
        var c = new Cluster(servers, 5);
        for (int i = 0; i < devices; i++) c.addUser("u" + i, "phone");
        // give everyone 3 queued messages to catch up on
        for (int i = 0; i < devices; i++)
            for (int k = 0; k < 3; k++)
                c.boxes.append("u" + i + ":phone", new Msg("m" + i + "-" + k, "sys", "u" + i, "c" + i, k, "enc(x)"));
        long t0 = System.nanoTime();
        for (int i = 0; i < devices; i++) c.device("u" + i + ":phone").connect(c.homeFor("u" + i + ":phone"));
        c.net.runUntilIdle();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("sockets per server (mean)  : " + devices / servers);
        System.out.println("mailbox reads triggered    : " + devices);
        System.out.println("catch-up packets pushed    : " + c.net.sent);
        System.out.println("registry writes in 1 second: " + devices + "   <-- the registry, not the mailbox, is the hot spot");
        System.out.println("simulated in               : " + ms + " ms of real time");
    }

    static void capacity() {
        say("The back-of-envelope an interviewer wants to hear. Arithmetic, not simulation.");
        long users = 2_000_000_000L;
        long msgsPerDay = 100_000_000_000L;          // ~100B/day, WhatsApp's own public figure
        long avgQps = msgsPerDay / 86_400;
        long peakQps = avgQps * 3;
        long onlineFraction = 4;                      // 1 in 4 connected at once
        long concurrent = users / onlineFraction;
        long connsPerServer = 1_000_000;              // demonstrated by WhatsApp on FreeBSD
        long serversNeeded = concurrent / connsPerServer;
        long bytesPerMsg = 200;                       // ciphertext + envelope
        long undeliveredFraction = 100;               // 1% of a day's messages waiting at any time
        double queueGB = (double) msgsPerDay / undeliveredFraction * bytesPerMsg / 1e9;

        System.out.printf("users                                : %,d%n", users);
        System.out.printf("messages/day                         : %,d%n", msgsPerDay);
        System.out.printf("average messages/second              : %,d%n", avgQps);
        System.out.printf("peak (x3)                            : %,d/s%n", peakQps);
        System.out.printf("concurrently connected (1 in %d)      : %,d%n", onlineFraction, concurrent);
        System.out.printf("connection servers @ %,d sockets  : %,d%n", connsPerServer, serversNeeded);
        System.out.printf("undelivered queue, 1%% of a day's mail: %.0f GB   <-- tiny; this is NOT a storage problem%n", queueGB);
        System.out.println("""
                
                The shape of the answer: this system is enormous in CONNECTIONS and
                tiny in STORAGE, because delivered messages are deleted. Design the
                connection layer first; the database is almost an afterthought. A
                design that starts with "we shard the messages table" has already
                misread the problem.""");
    }

    // ------------------------------------------------------------------

    static Cluster pair(boolean verbose) {
        var c = new Cluster(3, 42);
        c.addUser("asha", "phone");
        c.addUser("bilal", "phone");
        c.net.verbose = verbose;
        return c;
    }

    static void say(String s) { System.out.println(s + "\n"); }
}
