import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The OTHER family of replication: one node is in charge.
 *
 * Every write goes to the leader; followers copy it. The only real question is
 * *when the leader is allowed to say "done"* -- before the followers have it,
 * after some have it, or after all have it. That one choice decides how fast
 * writes are, how available they are, and how many acknowledged writes you can
 * lose when the leader dies. This class exists so the lab can measure all
 * three answers side by side.
 */
class LeaderCluster {

    enum Mode {
        /** Say "done" as soon as the leader has it. Fast; loses writes on failover. */
        ASYNC,
        /** Say "done" once `acksNeeded` followers have it. The middle ground. */
        SEMI_SYNC,
        /** Say "done" only when every live follower has it. Safe; one slow follower stalls everything. */
        SYNC
    }

    Mode mode = Mode.ASYNC;
    int acksNeeded = 1;
    /**
     * The floor Kafka calls `min.insync.replicas`, and it exists because of a
     * trap in SYNC mode: "wait for all LIVE followers" quietly becomes "wait
     * for nobody" once the last follower drops out, so the setting that was
     * supposed to be the safest silently degrades to the least safe. This
     * counts the leader, so 1 means "no floor at all".
     */
    int minInSync = 1;

    String leader;
    final List<String> members = new ArrayList<>();
    final Map<String, Map<String, String>> data = new HashMap<>();
    final Map<String, Boolean> alive = new HashMap<>();
    /** Writes handed to a follower but not yet applied -- this queue IS replication lag. */
    final Map<String, Deque<String[]>> pending = new HashMap<>();

    long acked = 0, rejected = 0, ackedThenLost = 0, staleReads = 0;

    LeaderCluster(int n) {
        for (int i = 0; i < n; i++) { String id = "n" + i; members.add(id); data.put(id, new HashMap<>()); alive.put(id, true); pending.put(id, new ArrayDeque<>()); }
        leader = "n0";
    }

    List<String> followers() { return members.stream().filter(m -> !m.equals(leader)).toList(); }

    /** Returns true if the client is told the write succeeded. */
    boolean write(String key, String value) {
        if (!alive.get(leader)) { rejected++; return false; }
        data.get(leader).put(key, value);
        int acks = 0;
        for (String f : followers()) {
            if (!alive.get(f)) continue;
            if (mode == Mode.SYNC) { data.get(f).put(key, value); acks++; }
            else if (mode == Mode.SEMI_SYNC && acks < acksNeeded) { data.get(f).put(key, value); acks++; }
            else pending.get(f).addLast(new String[]{key, value});   // catches up later, or never
        }
        int needed = switch (mode) {
            case ASYNC -> 0;
            case SEMI_SYNC -> acksNeeded;
            case SYNC -> (int) followers().stream().filter(alive::get).count();
        };
        long live = 1 + followers().stream().filter(alive::get).count();
        if (live < minInSync) { rejected++; return false; }   // refuse rather than silently degrade
        if (acks >= needed) { acked++; return true; }
        rejected++; return false;
    }

    /** Let replication catch up by `steps` entries per follower. */
    void tick(int steps) {
        for (String f : followers()) {
            var q = pending.get(f);
            for (int i = 0; i < steps && !q.isEmpty(); i++) { String[] e = q.pollFirst(); if (alive.get(f)) data.get(f).put(e[0], e[1]); }
        }
    }

    String readFrom(String member, String key) { return data.get(member).get(key); }

    /**
     * The leader dies and a follower is promoted.
     *
     * Anything the old leader acknowledged but never managed to replicate is
     * now gone -- and the client was told it succeeded. That is not a bug in
     * the failover; it is the bill for saying "done" early.
     */
    void failover() {
        alive.put(leader, false);
        String old = leader;
        String best = null; int bestSize = -1;
        for (String f : followers()) if (alive.get(f) && data.get(f).size() > bestSize) { bestSize = data.get(f).size(); best = f; }
        if (best == null) return;
        for (var e : data.get(old).entrySet())
            if (!e.getValue().equals(data.get(best).get(e.getKey()))) ackedThenLost++;
        leader = best;
        for (String f : followers()) pending.get(f).clear();
    }
}
