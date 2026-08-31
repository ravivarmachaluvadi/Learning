import java.util.*;

/** Hand-built histories, each one making exactly one point. These are the pictures in the README. */
final class Scenarios {

    /** A write, then a read that sees it. The boring case — and the one everything else is measured against. */
    static History ok() {
        return History.of("A: write then read",
            Op.put(1, 1, 0, 4),
            Op.get(2, 1, 6, 10));
    }

    /** Meera sees her result; her father, looking a moment later, does not. */
    static History staleRead() {
        return History.of("B: the later reader sees the older value",
            Op.put(1, 1, 0, 4),
            Op.get(2, 0, 6, 10));
    }

    /** A slow write overlapping two reads: no violation, because nobody could tell. */
    static History concurrentOk() {
        return History.of("C: a slow write, read before and after",
            Op.put(1, 1, 0, 14),
            Op.get(2, 0, 2, 5),
            Op.get(3, 1, 7, 11));
    }

    /** Two different readers, while a write is still in flight, disagree about the past. */
    static History twoReadersDisagree() {
        return History.of("D: two readers disagree (the quorum read without write-back)",
            Op.put(1, 1, 0, 24),
            Op.get(2, 1, 2, 8),
            Op.get(3, 0, 12, 18));
    }

    /** The same client reads the new value and then the old one. */
    static History flipFlop() {
        return History.of("E: one client sees the value appear, then disappear",
            Op.put(1, 1, 0, 24),
            Op.get(2, 1, 2, 8),
            Op.get(2, 0, 12, 18));
    }

    /** The client's write timed out. Later reads see it — which is a perfectly good explanation. */
    static History pendingSeen() {
        return History.of("F: a timed-out write that did happen",
            Op.pendingPut(1, 1, 0),
            Op.get(2, 1, 10, 14),
            Op.get(3, 1, 20, 24));
    }

    /** Same timed-out write, but now it appears and then vanishes. No explanation exists. */
    static History pendingFlickers() {
        return History.of("G: a timed-out write that appears, then vanishes",
            Op.pendingPut(1, 1, 0),
            Op.get(2, 1, 10, 14),
            Op.get(3, 0, 20, 24));
    }

    /** Perfectly linearizable, and an update is still lost. Linearizability is not a transaction. */
    static History lostUpdate() {
        return History.of("H: linearizable, and an update is still lost",
            Op.get(1, 0, 0, 2),
            Op.get(2, 0, 3, 5),
            Op.put(1, 1, 10, 12),
            Op.put(2, 2, 13, 15),
            Op.get(3, 2, 20, 22));
    }

    /** Compare-and-set makes the same two clients safe: the loser is told it lost. */
    static History casProtects() {
        return History.of("I: the same race, with compare-and-set",
            Op.get(1, 0, 0, 2),
            Op.get(2, 0, 3, 5),
            Op.cas(1, 0, 1, true, 10, 12),
            Op.cas(2, 0, 2, false, 13, 15),
            Op.get(3, 1, 20, 22));
    }

    /** Two registers. Each one on its own is sequentially consistent; together they are not. */
    static History twoObjects() {
        History h = new History(); h.name = "J: two objects (the locality demo)";
        Op a = Op.put(1, 1, 0, 4);  a.obj = "x";
        Op b = Op.get(1, 0, 6, 10); b.obj = "y";
        Op c = Op.put(2, 1, 0, 4);  c.obj = "y";
        Op d = Op.get(2, 0, 6, 10); d.obj = "x";
        return h.add(a).add(b).add(c).add(d);
    }

    static List<History> all() {
        return List.of(ok(), staleRead(), concurrentOk(), twoReadersDisagree(), flipFlop(),
                       pendingSeen(), pendingFlickers(), lostUpdate(), casProtects());
    }
}
