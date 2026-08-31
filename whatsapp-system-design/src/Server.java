import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A connection server (WhatsApp called these "chatd" boxes).
 *
 * Its job is NOT to store your chat history. Its job is to hold a very large
 * number of idle TCP sockets and move small packets between them. Everything
 * durable lives in the MailboxStore; everything routable lives in the Registry.
 * That split is what lets you kill one of these at any moment.
 */
class Server {

    final String id;
    private final Net net;
    private final Registry reg;
    private final MailboxStore boxes;
    private final Cluster cluster;

    /** deviceId -> the device object whose socket we are holding. */
    private final Map<String, Device> sockets = new HashMap<>();

    /** "deviceId|msgId" pairs we pushed and are still waiting for a DEVICE_ACK on. */
    private final Set<String> inFlight = new HashSet<>();

    long handledSends = 0, pushes = 0, fanoutWrites = 0, sweepPushes = 0;

    /**
     * EXERCISES rung 6. Turn this on with `java src/Tests.java bug=delete-on-push`
     * and find out which checks catch it. It breaks invariant 2: "nothing is
     * deleted before the recipient device confirms it."
     */
    static boolean BUG_DELETE_ON_PUSH = false;

    private static String key(String deviceId, String msgId) { return deviceId + "|" + msgId; }

    Server(String id, Net net, Registry reg, MailboxStore boxes, Cluster cluster) {
        this.id = id; this.net = net; this.reg = reg; this.boxes = boxes; this.cluster = cluster;
    }

    boolean holds(String deviceId) { return sockets.containsKey(deviceId); }
    int socketCount() { return sockets.size(); }

    // ---- connection lifecycle -------------------------------------------

    void onConnect(Device d) {
        sockets.put(d.deviceId, d);
        reg.bind(d.deviceId, id);                      // now the world can find this device
        net.note(d.deviceId + " connected to " + id + " (registry updated)");
        drainMailbox(d.deviceId);                       // catch-up: everything queued while away
    }

    void onDisconnect(String deviceId) {
        sockets.remove(deviceId);
        inFlight.removeIf(k -> k.startsWith(deviceId + "|"));   // those pushes are dead now
        reg.unbind(deviceId);
        net.note(deviceId + " disconnected from " + id);
    }

    /** Crash: sockets vanish. Note what we do NOT do here -- clean the registry. */
    void crash() {
        sockets.clear();
        reg.leaveStaleEntriesFor(id);
        net.kill(id);
        net.note("*** SERVER " + id + " CRASHED (" + " sockets lost, registry rows now stale)");
    }

    // ---- the send path ---------------------------------------------------

    /**
     * Step 1 of delivery. The sender's phone handed us a message.
     *
     * Order of operations is the lesson: we FAN OUT INTO MAILBOXES FIRST, and
     * only then ack the sender. Acking first would mean a crash here loses a
     * message the sender believes was sent.
     */
    void onSend(Device from, Msg m) {
        handledSends++;

        // Every device of the recipient, plus the sender's OTHER devices, so a
        // laptop and a phone show the same chat. Multi-device is not a feature
        // bolted on later: it is what "recipient" means.
        //
        // A group ("#family") is not a special case here on purpose: it is the
        // same loop with a longer member list. THAT is fan-out-on-write -- one
        // stored copy per recipient device, no shared group mailbox anywhere.
        Set<String> targets = new HashSet<>();
        for (String member : cluster.recipientsOf(m.toUser())) targets.addAll(reg.devicesOf(member));
        for (String d : reg.devicesOf(m.fromUser())) if (!d.equals(from.deviceId)) targets.add(d);
        targets.remove(from.deviceId);

        for (String deviceId : targets) {
            boxes.append(deviceId, m);                  // durable BEFORE the ack
            fanoutWrites++;
        }
        net.note(id + " stored " + m + " into " + targets.size() + " mailbox(es)");

        // one grey tick: "the service has it", NOT "the recipient has it"
        net.send(id, from.deviceId, () -> from.onServerAck(m.id()));

        for (String deviceId : targets) tryPush(deviceId, m);
    }

    /**
     * Step 2. Is the target device online, and if so, on which of us?
     * This is the registry lookup that a single-server chat app never needs.
     */
    void tryPush(String deviceId, Msg m) {
        String home = reg.serverOf(deviceId);
        if (home == null) {
            net.note("  " + deviceId + " is offline -> message waits in its mailbox");
            return;                                     // no push. The mailbox is the plan.
        }
        if (home.equals(id)) { deliverToSocket(deviceId, m); return; }
        net.send(id, home, () -> cluster.server(home).deliverToSocket(deviceId, m));
    }

    /** Step 3. Write it to the socket we are holding, then wait for the device's ack. */
    void deliverToSocket(String deviceId, Msg m) {
        Device d = sockets.get(deviceId);
        if (d == null) {
            // The registry said we hold this socket. We do not. The registry lied.
            net.note("  !! " + id + " was told to deliver to " + deviceId + " but holds no such socket (stale registry)");
            return;
        }
        pushes++;
        inFlight.add(key(deviceId, m.id()));
        net.send(id, deviceId, () -> d.onDeliver(m));
        if (BUG_DELETE_ON_PUSH) boxes.delete(deviceId, m.id());   // planted bug

        // Retry timer: if no DEVICE_ACK comes back, push again. This is where
        // at-least-once delivery -- and therefore duplicates -- is born.
        net.after(500, () -> {
            if (inFlight.contains(key(deviceId, m.id())) && sockets.containsKey(deviceId)) {
                boxes.countRedelivery();
                net.note("  retry: no device-ack for " + m + ", pushing again to " + deviceId);
                deliverToSocket(deviceId, m);
            }
        });
    }

    /** Step 4. The device has it on disk. Now, and only now, delete the copy. */
    void onDeviceAck(String deviceId, String msgId, Msg m) {
        inFlight.remove(key(deviceId, msgId));
        boolean removed = boxes.delete(deviceId, msgId);
        net.note(id + " got device-ack for " + msgId + " from " + deviceId + (removed ? " -> mailbox copy deleted" : " (already deleted; duplicate ack)"));
        if (removed && m != null) {
            // two grey ticks travel back to the sender
            String senderDevice = cluster.primaryDeviceOf(m.fromUser());
            if (senderDevice == null) return;               // sender no longer exists; drop the receipt
            String home = reg.serverOf(senderDevice);
            if (home != null) net.send(id, home, () -> cluster.server(home).relayReceipt(senderDevice, msgId, Kind.DELIVERED_RECEIPT));
        }
    }

    void relayReceipt(String deviceId, String msgId, Kind kind) {
        Device d = sockets.get(deviceId);
        if (d == null) return;                          // sender went offline; receipts are best-effort
        net.send(id, deviceId, () -> d.onReceipt(msgId, kind));
    }

    void onReadReceipt(String readerDeviceId, Msg m) {
        String senderDevice = cluster.primaryDeviceOf(m.fromUser());
        if (senderDevice == null) return;
        String home = reg.serverOf(senderDevice);
        if (home != null) net.send(id, home, () -> cluster.server(home).relayReceipt(senderDevice, m.id(), Kind.READ_RECEIPT));
    }

    /**
     * The delivery sweeper -- and the most under-appreciated box in the design.
     *
     * Every push in this file can be lost: the packet to the device, the ack
     * back, or the server-to-server hop that carries the push at all. If that
     * last one dies, nothing on the receiving server ever knew a push was owed,
     * so no retry timer exists and the message sits in the mailbox for ever.
     * (That is not hypothetical -- it is the bug this lab hit at 30% loss:
     * 4 of 20 messages stranded with one grey tick.)
     *
     * The fix is not a better push. It is to stop treating the push as the
     * mechanism at all: THE QUEUE IS THE MECHANISM, and a periodic re-scan of
     * every connected device's mailbox is what actually delivers. The push is
     * only there to make the common case fast.
     */
    void startSweeper(long periodMs, long untilMs) {
        net.after(periodMs, () -> {
            if (net.now > untilMs) return;              // simulation horizon only; real ones run forever
            if (!net.isDown(id)) {
                for (String deviceId : new ArrayList<>(sockets.keySet())) {
                    for (var e : new ArrayList<>(boxes.box(deviceId))) {
                        if (!inFlight.contains(key(deviceId, e.msg().id()))) {
                            sweepPushes++;
                            net.note("  sweeper on " + id + ": " + e.msg() + " is still queued for " + deviceId + " -> pushing again");
                            deliverToSocket(deviceId, e.msg());
                        }
                    }
                }
            }
            startSweeper(periodMs, untilMs);
        });
    }

    /** Reconnect catch-up: everything that piled up while the device was away. */
    void drainMailbox(String deviceId) {
        var pending = boxes.box(deviceId).stream().map(MailboxStore.Entry::msg).toList();
        if (!pending.isEmpty()) net.note("  catch-up: " + id + " replaying " + pending.size() + " queued message(s) to " + deviceId);
        for (Msg m : pending) deliverToSocket(deviceId, m);
    }
}
