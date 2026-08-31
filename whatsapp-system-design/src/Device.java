import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One phone or laptop. The half of the system the user actually touches.
 *
 * Two of its jobs are load-bearing for correctness and are easy to forget when
 * you sketch this design on a whiteboard:
 *
 *  - it MINTS message ids (so retries are recognisable), and
 *  - it DEDUPLICATES on those ids (so at-least-once delivery from the server
 *    still looks like exactly-once to the human).
 */
class Device {

    final String deviceId;
    final String user;
    private final Net net;
    private final Cluster cluster;

    Server server;                 // which connection server we are attached to
    boolean online = false;

    /** Every message id this device has ever accepted. The dedup set. */
    private final Set<String> seen = new HashSet<>();

    /** What the human actually sees, in the order it appeared on screen. */
    final List<Msg> chatWindow = new ArrayList<>();

    /** Per-conversation tick state on the sender side: 1 = sent, 2 = delivered, 3 = read. */
    final Map<String, Integer> ticks = new LinkedHashMap<>();

    /** Sent-but-unacked, for the client-side retry. Same id on every retry. */
    private final Map<String, Msg> awaitingServerAck = new HashMap<>();

    private final Map<String, Long> nextSeq = new HashMap<>();

    long duplicatesSuppressed = 0, outOfOrderSeen = 0, clientRetries = 0;

    /** EXERCISES rung 6: breaks invariant 4, "a device accepts each id at most once". */
    static boolean BUG_NO_DEDUP = false;
    /** EXERCISES rung 6: breaks invariant 3, "message identity belongs to the sender". */
    static boolean BUG_NEW_ID_ON_RETRY = false;
    private final Map<String, Long> lastSeqPerConv = new HashMap<>();

    Device(String deviceId, String user, Net net, Cluster cluster) {
        this.deviceId = deviceId; this.user = user; this.net = net; this.cluster = cluster;
    }

    // ---- connectivity ----------------------------------------------------

    void connect(Server s) {
        online = true; server = s;
        s.onConnect(this);
        // resend anything we were never told was stored -- with the ORIGINAL id
        for (Msg m : new ArrayList<>(awaitingServerAck.values())) {
            clientRetries++;
            net.note(deviceId + " re-sending unacked " + m + " (same id, so it cannot double-post)");
            net.send(deviceId, s.id, () -> s.onSend(this, m));
        }
    }

    void goOffline() {
        if (server != null) server.onDisconnect(deviceId);
        online = false; server = null;
        net.note(deviceId + " went offline (tunnel / plane / battery)");
    }

    // ---- sending ---------------------------------------------------------

    Msg send(String toUser, String convId, String text) {
        long seq = nextSeq.merge(convId, 1L, Long::sum);
        // The id is minted HERE. A server never invents one. Invariant: identity
        // belongs to the sender, so a retry is provably the same message.
        String id = deviceId + "-" + convId + "-" + seq;
        Msg m = new Msg(id, user, toUser, convId, seq, "enc(" + text + ")");
        chatWindow.add(m);
        ticks.put(m.id(), 0);                       // 0 = clock icon: not even stored yet
        awaitingServerAck.put(m.id(), m);
        if (!online) { net.note(deviceId + " is offline; " + m + " sits in the outbox with a clock icon"); return m; }
        Server s = server;
        net.send(deviceId, s.id, () -> s.onSend(this, m));
        scheduleRetry(m, 800, 1);
        return m;
    }

    /**
     * Retry until the server acks -- REPEATEDLY, not once.
     *
     * A single retry is the classic beginner bug: on a 35%-loss link, ~12% of
     * messages lose both the original and the one retry and are gone forever.
     * (EXERCISES rung 6 makes you reproduce exactly that.) The delay doubles
     * each time, because a fleet of clients retrying on a fixed timer after an
     * outage is how you take your own servers down a second time.
     */
    private void scheduleRetry(Msg m, long delay, int attempt) {
        net.after(delay, () -> {
            if (!awaitingServerAck.containsKey(m.id())) return;      // stored; nothing to do
            if (attempt > 8) { net.note(deviceId + " gave up on " + m + " -- shows a red '!' to the user"); return; }
            if (online && server != null) {
                clientRetries++;
                Msg out = m;
                if (BUG_NEW_ID_ON_RETRY) {                        // planted bug
                    out = new Msg(m.id() + "#retry" + attempt, m.fromUser(), m.toUser(), m.convId(), m.seq(), m.ciphertext());
                    awaitingServerAck.remove(m.id());
                    awaitingServerAck.put(out.id(), out);
                    ticks.put(out.id(), 0);
                }
                net.note(deviceId + " no server-ack for " + m + " -> retry #" + attempt
                        + (BUG_NEW_ID_ON_RETRY ? " with a NEW id " + out.id() : " with the SAME id"));
                Server cur = server;
                final Msg send = out;
                net.send(deviceId, cur.id, () -> cur.onSend(this, send));
            }
            scheduleRetry(m, Math.min(delay * 2, 5_000), attempt + 1);   // exponential backoff
        });
    }

    // ---- receiving -------------------------------------------------------

    void onServerAck(String msgId) {
        awaitingServerAck.remove(msgId);
        ticks.merge(msgId, 1, Math::max);
        net.note(deviceId + " got server-ack for " + msgId + "  [one grey tick]");
    }

    void onDeliver(Msg m) {
        // Dedup FIRST. The server may honestly send this twice; the human must
        // see it once. at-least-once (server) + dedup (client) = effectively-once.
        if (!BUG_NO_DEDUP && !seen.add(m.id())) {
            duplicatesSuppressed++;
            net.note(deviceId + " received " + m + " AGAIN -> suppressed by id (not shown twice)");
            ackTo(m);                                // still ack, or it retries forever
            return;
        }
        long last = lastSeqPerConv.getOrDefault(m.convId(), 0L);
        if (m.seq() < last) {
            outOfOrderSeen++;
            net.note(deviceId + " !! received " + m + " AFTER seq " + last + " -- out of order");
        }
        lastSeqPerConv.put(m.convId(), Math.max(last, m.seq()));
        chatWindow.add(m);
        net.note(deviceId + " SHOWED message " + m + " to the user");
        ackTo(m);
    }

    private void ackTo(Msg m) {
        if (!online || server == null) return;
        Server s = server;
        net.send(deviceId, s.id, () -> s.onDeviceAck(deviceId, m.id(), m));
    }

    /** The human opened the chat. */
    void read(Msg m) {
        if (!online || server == null) return;
        Server s = server;
        net.send(deviceId, s.id, () -> s.onReadReceipt(deviceId, m));
    }

    void onReceipt(String msgId, Kind kind) {
        int level = kind == Kind.DELIVERED_RECEIPT ? 2 : 3;
        ticks.merge(msgId, level, Math::max);
        net.note(deviceId + " -> " + msgId + " is now " + tickLabel(level));
    }

    static String tickLabel(int t) {
        return switch (t) {
            case 0 -> "clock (not stored yet)";
            case 1 -> "one grey tick (server has it)";
            case 2 -> "two grey ticks (device has it)";
            default -> "two blue ticks (human read it)";
        };
    }

    String tickOf(String msgId) { return tickLabel(ticks.getOrDefault(msgId, 0)); }
}
