import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * One durable FIFO queue PER DEVICE. Not per user, not per chat.
 *
 * This is the single most important storage decision in the whole design, and
 * two rules hang off it:
 *
 *  1. A message is appended here BEFORE the sender is told "sent" (one tick).
 *     If the box is not written, the sender has not been acked, so the sender
 *     will retry. Nothing is ever only in RAM.
 *
 *  2. A message is deleted ONLY when the receiving DEVICE says it has it --
 *     not when a server sent it, not when the network accepted it. That is the
 *     durability invariant that makes "phone died mid-delivery" survivable.
 */
class MailboxStore {

    record Entry(Msg msg, int attempts) {}

    private final Map<String, Deque<Entry>> boxes = new HashMap<>();

    long appends = 0, deletes = 0, redeliveries = 0;

    void append(String deviceId, Msg m) {
        boxes.computeIfAbsent(deviceId, d -> new ArrayDeque<>()).addLast(new Entry(m, 0));
        appends++;
    }

    Deque<Entry> box(String deviceId) { return boxes.computeIfAbsent(deviceId, d -> new ArrayDeque<>()); }

    int size(String deviceId) { return box(deviceId).size(); }

    /** Deleting on device-ack. Returns false if it was already gone (a duplicate ack). */
    boolean delete(String deviceId, String msgId) {
        boolean removed = box(deviceId).removeIf(e -> e.msg().id().equals(msgId));
        if (removed) deletes++;
        return removed;
    }

    void countRedelivery() { redeliveries++; }

    int totalUndelivered() { return boxes.values().stream().mapToInt(Deque::size).sum(); }
}
