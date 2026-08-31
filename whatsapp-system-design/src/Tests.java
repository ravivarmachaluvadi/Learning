import java.util.List;
import java.util.Set;

/**
 * Scenario tests. Each one is named after a thing that happens to real chat
 * apps, and each is meant to be READ as much as run:
 *
 *     java src/Tests.java
 */
public class Tests {

    static int passed = 0, failed = 0;

    public static void main(String[] args) {
        for (String a : args) {
            switch (a) {
                case "bug=delete-on-push"   -> Server.BUG_DELETE_ON_PUSH = true;
                case "bug=no-dedup"         -> Device.BUG_NO_DEDUP = true;
                case "bug=new-id-on-retry"  -> Device.BUG_NEW_ID_ON_RETRY = true;
                default -> System.out.println("unknown flag: " + a
                        + "  (try bug=delete-on-push | bug=no-dedup | bug=new-id-on-retry)");
            }
            if (a.startsWith("bug=")) System.out.println(">>> PLANTED BUG ACTIVE: " + a + " <<<\n");
        }
        happyPath_oneToOne_reachesTwoGreyTicks();
        offlineRecipient_messageWaitsInMailbox_andArrivesOnReconnect();
        mailboxCopy_survivesUntilTheDEVICEAcks_notWhenTheServerSends();
        droppedDeviceAck_causesRedelivery_andTheClientSuppressesTheDuplicate();
        droppedServerAck_causesClientRetry_withSameId_soNothingIsPostedTwice();
        serverCrash_leavesStaleRegistryRow_messageStuckUntilTheRowIsPurged();
        multiDevice_fanOutReachesEveryDevice_includingTheSendersOwnLaptop();
        groupOf5_writesOneMailboxCopyPerRecipientDevice();
        readReceipt_upgradesTwoGreyTicksToBlue();
        lossyNetwork_30pctDrop_nothingLost_nothingShownTwice();
        withoutTheSweeper_someMessagesStrandForever_provingThePushIsNotTheGuarantee();

        System.out.println("\n=== " + passed + " passed, " + failed + " failed ===");
        boolean bugged = args.length > 0;
        if (bugged) System.out.println("A planted bug should FAIL some checks. Which ones, and which invariant do they protect?");
        if (failed > 0 && !bugged) System.exit(1);
    }

    // -------------------------------------------------------------------

    static void happyPath_oneToOne_reachesTwoGreyTicks() {
        var c = base();
        c.connectAll();
        Msg m = c.device("asha:phone").send("bilal", "asha-bilal", "on my way");
        c.net.runUntilIdle();

        check("happyPath: bilal's phone showed exactly 1 message",
                c.device("bilal:phone").chatWindow.size() == 1);
        check("happyPath: asha sees two grey ticks",
                c.device("asha:phone").tickOf(m.id()).startsWith("two grey"));
        check("happyPath: nothing left in any mailbox", c.boxes.totalUndelivered() == 0);
    }

    static void offlineRecipient_messageWaitsInMailbox_andArrivesOnReconnect() {
        var c = base();
        c.device("asha:phone").connect(c.homeFor("asha:phone"));   // bilal stays offline
        Msg m = c.device("asha:phone").send("bilal", "asha-bilal", "call me");
        c.net.runUntilIdle();

        check("offline: asha sees ONE grey tick, not two",
                c.device("asha:phone").tickOf(m.id()).startsWith("one grey"));
        check("offline: the message is sitting in bilal's mailbox",
                c.boxes.size("bilal:phone") == 1);
        check("offline: bilal has been shown nothing", c.device("bilal:phone").chatWindow.isEmpty());

        c.device("bilal:phone").connect(c.homeFor("bilal:phone"));  // out of the tunnel
        c.net.runUntilIdle();

        check("reconnect: catch-up delivered the queued message",
                c.device("bilal:phone").chatWindow.size() == 1);
        check("reconnect: mailbox is now empty", c.boxes.size("bilal:phone") == 0);
        check("reconnect: asha's tick upgraded to two grey",
                c.device("asha:phone").tickOf(m.id()).startsWith("two grey"));
    }

    static void mailboxCopy_survivesUntilTheDEVICEAcks_notWhenTheServerSends() {
        var c = base();
        c.net.dropRate = 0.0;
        c.connectAll();
        // Kill bilal's phone the instant the server pushes: the push happens,
        // the device-ack never does.
        c.device("asha:phone").send("bilal", "asha-bilal", "important");
        Server home = c.server(c.registryServerOf("bilal:phone"));
        while (home.pushes == 0 && c.net.now < 2_000) c.net.runFor(1);   // stop exactly at the push
        c.device("bilal:phone").goOffline();
        c.net.runUntilIdle();

        check("durability: the copy is STILL in the mailbox after the push",
                c.boxes.size("bilal:phone") == 1);
        check("durability: nothing was deleted on send", c.boxes.deletes == 0);
    }

    static void droppedDeviceAck_causesRedelivery_andTheClientSuppressesTheDuplicate() {
        var c = base();
        c.connectAll();
        c.net.dupRate = 1.0;                    // every packet arrives twice: forced redelivery
        c.device("asha:phone").send("bilal", "asha-bilal", "hello?");
        c.net.runUntilIdle();

        check("dedup: the duplicate reached the device",
                c.device("bilal:phone").duplicatesSuppressed >= 1);
        check("dedup: the human still saw it exactly once",
                c.device("bilal:phone").chatWindow.size() == 1);
    }

    static void droppedServerAck_causesClientRetry_withSameId_soNothingIsPostedTwice() {
        var c = base();
        c.connectAll();
        c.net.runUntilIdle();
        c.net.dropRate = 0.35;                  // flaky uplink: some acks die
        for (int i = 0; i < 8; i++) c.device("asha:phone").send("bilal", "asha-bilal", "msg" + i);
        c.net.runUntilIdle();
        c.net.dropRate = 0.0;
        c.net.runFor(5000);                     // let retries and catch-up finish

        long distinct = c.device("bilal:phone").chatWindow.stream().map(Msg::id).distinct().count();
        check("retry: client retried at least once", c.device("asha:phone").clientRetries >= 1);
        check("retry: bilal's window has no duplicate ids",
                distinct == c.device("bilal:phone").chatWindow.size());
    }

    static void serverCrash_leavesStaleRegistryRow_messageStuckUntilTheRowIsPurged() {
        var c = base();
        c.connectAll();
        c.net.runUntilIdle();
        String dead = c.registryServerOf("bilal:phone");
        c.server(dead).crash();                 // sockets gone, registry row NOT cleaned

        c.device("asha:phone").send("bilal", "asha-bilal", "you there?");
        c.net.runUntilIdle();

        check("crash: bilal saw nothing (routed at a dead server)",
                c.device("bilal:phone").chatWindow.isEmpty());
        check("crash: but the message is safe in the mailbox",
                c.boxes.size("bilal:phone") == 1);

        c.registry.purge(dead);                 // what a session-expiry/heartbeat does
        c.net.revive(dead);
        c.device("bilal:phone").connect(c.homeFor("bilal:phone"));
        c.net.runUntilIdle();

        check("crash: after reconnect the message is delivered",
                c.device("bilal:phone").chatWindow.size() == 1);
    }

    static void multiDevice_fanOutReachesEveryDevice_includingTheSendersOwnLaptop() {
        var c = new Cluster(3, 7);
        c.addUser("asha", "phone", "laptop");
        c.addUser("bilal", "phone", "laptop");
        c.connectAll();
        c.device("asha:phone").send("bilal", "asha-bilal", "dinner?");
        c.net.runUntilIdle();

        check("multi-device: bilal's phone got it", c.device("bilal:phone").chatWindow.size() == 1);
        check("multi-device: bilal's laptop got it", c.device("bilal:laptop").chatWindow.size() == 1);
        check("multi-device: asha's OWN laptop got a copy too",
                c.device("asha:laptop").chatWindow.size() == 1);
        check("multi-device: 3 mailbox copies were written for 1 typed message",
                c.boxes.appends == 3);
    }

    static void groupOf5_writesOneMailboxCopyPerRecipientDevice() {
        var c = new Cluster(4, 11);
        for (String u : List.of("asha", "bilal", "chen", "dia", "eve")) c.addUser(u, "phone");
        c.addGroup("#trip", "asha", "bilal", "chen", "dia", "eve");
        c.connectAll();
        c.device("asha:phone").send("#trip", "trip", "landed!");
        c.net.runUntilIdle();

        check("group: 4 recipients each got the message",
                Set.of("bilal", "chen", "dia", "eve").stream()
                        .allMatch(u -> c.device(u + ":phone").chatWindow.size() == 1));
        check("group: exactly 4 mailbox copies for 1 typed message (fan-out on write)",
                c.boxes.appends == 4);
        check("group: the sender's own device was not sent a copy",
                c.device("asha:phone").chatWindow.size() == 1);   // only its own local echo
    }

    static void readReceipt_upgradesTwoGreyTicksToBlue() {
        var c = base();
        c.connectAll();
        Msg m = c.device("asha:phone").send("bilal", "asha-bilal", "you up?");
        c.net.runUntilIdle();
        c.device("bilal:phone").read(m);                 // human opens the chat
        c.net.runUntilIdle();

        check("read: asha now sees two blue ticks",
                c.device("asha:phone").tickOf(m.id()).startsWith("two blue"));
    }

    static void lossyNetwork_30pctDrop_nothingLost_nothingShownTwice() {
        var c = base();
        c.connectAll();
        c.net.runUntilIdle();
        c.startSweepers(2_000, 60_000);         // the queue, not the push, is the guarantee
        c.net.dropRate = 0.30;
        for (int i = 0; i < 20; i++) c.device("asha:phone").send("bilal", "asha-bilal", "m" + i);
        c.net.runFor(20_000);
        c.net.dropRate = 0.0;
        c.net.runFor(45_000);

        var window = c.device("bilal:phone").chatWindow;
        long distinct = window.stream().map(Msg::id).distinct().count();
        check("lossy: all 20 messages eventually arrived (delivered=" + distinct + "/20)", distinct == 20);
        check("lossy: none was shown twice (window=" + window.size() + ")", window.size() == 20);
        check("lossy: the client had to suppress duplicates (" +
                c.device("bilal:phone").duplicatesSuppressed + ")",
                c.device("bilal:phone").duplicatesSuppressed >= 1);
    }

    /**
     * The control experiment for the one above. Same seed, same loss rate, no
     * sweeper -- and messages are stranded in mailboxes with one grey tick,
     * because the server-to-server push that was owed to them was dropped and
     * nothing remembers to try again.
     */
    static void withoutTheSweeper_someMessagesStrandForever_provingThePushIsNotTheGuarantee() {
        var c = base();
        c.connectAll();
        c.net.runUntilIdle();
        c.net.dropRate = 0.30;
        for (int i = 0; i < 20; i++) c.device("asha:phone").send("bilal", "asha-bilal", "m" + i);
        c.net.runFor(20_000);
        c.net.dropRate = 0.0;
        c.net.runFor(45_000);                    // a perfect network from here on, forever

        int stranded = c.boxes.size("bilal:phone");
        check("no-sweeper: " + stranded + " message(s) stranded even on a now-perfect network",
                stranded > 0);
        check("no-sweeper: bilal saw only " + c.device("bilal:phone").chatWindow.size() + "/20",
                c.device("bilal:phone").chatWindow.size() < 20);
    }

    // -------------------------------------------------------------------

    static Cluster base() {
        var c = new Cluster(3, 42);
        c.addUser("asha", "phone");
        c.addUser("bilal", "phone");
        return c;
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
        if (ok) passed++; else failed++;
    }
}
