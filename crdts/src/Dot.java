/**
 * A "dot" is a globally unique name for one single event, made without asking
 * anybody: the replica that did it, plus how many things that replica had done
 * before. ("phone-a", 7) is the 7th action ever taken on phone-a, and no other
 * machine in the universe can accidentally produce that same pair.
 *
 * This is the same trick as the site-id tie-break in the google-docs lab, and
 * the same shape as one entry of a version vector from the quorums lab.
 */
public record Dot(String replica, long counter) implements Comparable<Dot> {
    @Override public int compareTo(Dot o) {
        int c = Long.compare(counter, o.counter);
        return c != 0 ? c : replica.compareTo(o.replica);
    }
    @Override public String toString() { return replica + "#" + counter; }
}
