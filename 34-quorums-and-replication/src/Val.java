/**
 * One stored value, with everything needed to work out which of two copies is
 * newer -- and to admit when that question has no answer.
 *
 * `ts` is a WALL-CLOCK timestamp taken on the node that accepted the write.
 * It is here because most real systems use it (last-write-wins), and because
 * the lab measures what it costs when two machines' clocks disagree.
 */
record Val(String value, VersionVector vv, long ts, String writer) {
    @Override public String toString() { return "\"" + value + "\"" + vv + "@t" + ts; }
}
