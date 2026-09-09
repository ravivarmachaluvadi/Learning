import java.util.*;

/**
 * One Raft server.
 *
 * The whole protocol is here. Lines that enforce one of the five safety
 * properties are marked  // INVARIANT: ...  — those are the load-bearing lines,
 * and every one of them can be switched off through Bugs to watch what it was
 * holding up.
 */
class RaftNode {
    enum Role { FOLLOWER, CANDIDATE, LEADER }

    // Timing, in logical ticks (read a tick as ~1ms).
    static int ELECTION_MIN = 150, ELECTION_MAX = 300, HEARTBEAT = 50;

    final int id;
    final int[] peers;          // ids of ALL servers, this one included
    final Cluster cluster;
    final Bugs bugs;

    // ---- persistent state: real Raft fsyncs these BEFORE replying to anyone ----
    int currentTerm = 0;
    int votedFor = -1;          // -1 = have not voted this term
    final List<Entry> log = new ArrayList<>();   // log[0] is a sentinel; real entries start at index 1

    // ---- volatile state ----
    Role role = Role.FOLLOWER;
    int commitIndex = 0;        // highest index known to be safely replicated
    int lastApplied = 0;        // highest index handed to the state machine
    final List<String> applied = new ArrayList<>();  // the state machine itself
    int leaderId = -1;
    boolean alive = true;

    // ---- leader-only volatile state ----
    int[] nextIndex, matchIndex;
    final Set<Integer> votesReceived = new HashSet<>();

    long electionDeadline, heartbeatDeadline;

    RaftNode(int id, int[] peers, Cluster cluster, Bugs bugs) {
        this.id = id; this.peers = peers; this.cluster = cluster; this.bugs = bugs;
        this.log.add(new Entry(0, "<sentinel>"));
        this.nextIndex = new int[peers.length];
        this.matchIndex = new int[peers.length];
        resetElectionTimer(0);
    }

    // ================= log helpers =================
    int lastLogIndex() { return log.size() - 1; }
    int lastLogTerm()  { return log.get(log.size() - 1).term(); }
    int majority()     { return peers.length / 2 + 1; }

    // ================= the clock =================
    void tick(long now) {
        if (!alive) return;
        if (role == Role.LEADER) {
            if (now >= heartbeatDeadline) {
                heartbeatDeadline = now + HEARTBEAT;
                for (int p : peers) if (p != id) sendAppend(p);
            }
        } else if (now >= electionDeadline) {
            startElection(now);
        }
        applyCommitted();
    }

    void resetElectionTimer(long now) {
        // INVARIANT (liveness): timeouts are RANDOMISED so two servers rarely
        // become candidates at the same instant. Without the spread you get
        // split votes forever -- see Bugs.fixedTimeout.
        int spread = bugs.fixedTimeout ? 0 : cluster.rng.nextInt(ELECTION_MAX - ELECTION_MIN + 1);
        electionDeadline = now + ELECTION_MIN + spread;
    }

    // ================= elections =================
    void startElection(long now) {
        role = Role.CANDIDATE;
        currentTerm++;                 // a new attempt is always a NEW term
        votedFor = id;                 // INVARIANT: this is the one vote this server has for this term
        votesReceived.clear();
        votesReceived.add(id);
        resetElectionTimer(now);
        cluster.event(id, "starts an election for term " + currentTerm
                + " (its log ends at index " + lastLogIndex() + ", term " + lastLogTerm() + ")");
        for (int p : peers) if (p != id)
            cluster.send(id, p, new Msg.RequestVote(currentTerm, id, lastLogIndex(), lastLogTerm()));
        checkElectionWon();            // a 1-server cluster wins instantly
    }

    /** Is the candidate's log at least as up to date as mine? (Raft paper section 5.4.1) */
    boolean candidateUpToDate(int candLastIndex, int candLastTerm) {
        if (candLastTerm != lastLogTerm()) return candLastTerm > lastLogTerm();
        return candLastIndex >= lastLogIndex();
    }

    void onRequestVote(Msg.RequestVote rv) {
        if (rv.term() > currentTerm) stepDown(rv.term(), "saw a higher term in a vote request");
        boolean granted = false;
        if (rv.term() == currentTerm) {
            // INVARIANT (Election Safety): at most one vote per term. Two leaders
            // in one term would need one server to vote twice; this line forbids it.
            boolean voteFree = bugs.voteTwice || votedFor == -1 || votedFor == rv.candidateId();
            // INVARIANT (Leader Completeness): never vote for a candidate that is
            // missing entries you already have. This is what stops a committed
            // entry from being erased by a future leader.
            boolean fresh = bugs.noUpToDateCheck || candidateUpToDate(rv.lastLogIndex(), rv.lastLogTerm());
            if (voteFree && fresh) {
                granted = true;
                votedFor = rv.candidateId();
                resetElectionTimer(cluster.now);
            } else if (!fresh) {
                cluster.event(id, "REFUSES to vote for S" + rv.candidateId()
                        + ": its log ends at (idx " + rv.lastLogIndex() + ", term " + rv.lastLogTerm()
                        + "), mine at (idx " + lastLogIndex() + ", term " + lastLogTerm() + ") -- mine is fresher");
            }
        }
        cluster.send(id, rv.candidateId(), new Msg.VoteReply(currentTerm, granted, id));
    }

    void onVoteReply(Msg.VoteReply r) {
        if (r.term() > currentTerm) { stepDown(r.term(), "a voter knew a higher term"); return; }
        if (role != Role.CANDIDATE || r.term() != currentTerm) return;  // a stale reply from an old election
        if (r.granted()) { votesReceived.add(r.voterId()); checkElectionWon(); }
    }

    void checkElectionWon() {
        if (role != Role.CANDIDATE || votesReceived.size() < majority()) return;
        role = Role.LEADER;
        leaderId = id;
        Arrays.fill(nextIndex, lastLogIndex() + 1);   // optimistic guess: followers match me
        Arrays.fill(matchIndex, 0);
        matchIndex[id] = lastLogIndex();
        heartbeatDeadline = cluster.now;              // heartbeat immediately, to stop other elections
        cluster.event(id, "WINS the election for term " + currentTerm
                + " with " + votesReceived.size() + "/" + peers.length + " votes " + votesReceived);
    }

    void stepDown(int newTerm, String why) {
        if (role != Role.FOLLOWER || currentTerm != newTerm)
            cluster.event(id, "steps down to follower, term " + currentTerm + " -> " + newTerm + " (" + why + ")");
        currentTerm = newTerm;
        role = Role.FOLLOWER;
        votedFor = -1;              // a NEW term means a fresh, unused vote
        votesReceived.clear();
        resetElectionTimer(cluster.now);
    }

    // ================= log replication =================

    /** A client write. Only a leader may accept one. Returns the index, or -1 if not the leader. */
    int clientRequest(String cmd) {
        if (role != Role.LEADER || !alive) return -1;
        // INVARIANT (Leader Append-Only): a leader only ever appends. It never
        // overwrites or deletes an entry of its own log.
        log.add(new Entry(currentTerm, cmd));
        matchIndex[id] = lastLogIndex();
        cluster.event(id, "accepts client write \"" + cmd + "\" as index " + lastLogIndex() + " (term " + currentTerm + ")");
        for (int p : peers) if (p != id) sendAppend(p);
        return lastLogIndex();
    }

    void sendAppend(int to) {
        int prev = Math.max(0, nextIndex[to] - 1);
        if (prev > lastLogIndex()) prev = lastLogIndex();
        List<Entry> entries = new ArrayList<>(log.subList(prev + 1, log.size()));
        cluster.send(id, to, new Msg.AppendEntries(currentTerm, id, prev, log.get(prev).term(), entries, commitIndex));
    }

    void onAppendEntries(Msg.AppendEntries ae) {
        if (ae.term() < currentTerm) {                       // a leader from the past
            cluster.send(id, ae.leaderId(), new Msg.AppendReply(currentTerm, false, 0, id));
            return;
        }
        if (ae.term() > currentTerm) stepDown(ae.term(), "saw a higher term from leader S" + ae.leaderId());
        role = Role.FOLLOWER;              // a candidate that hears a legitimate leader gives up at once
        leaderId = ae.leaderId();
        resetElectionTimer(cluster.now);   // this is what a heartbeat is FOR

        // INVARIANT (Log Matching): accept only if my log already agrees with the
        // leader at prevLogIndex. One index+term comparison certifies the entire
        // prefix, by induction -- that is the whole trick.
        boolean matches;
        if (bugs.noLogMatchCheck) matches = true;
        else matches = ae.prevLogIndex() <= lastLogIndex()
                    && log.get(ae.prevLogIndex()).term() == ae.prevLogTerm();

        if (!matches) {
            cluster.event(id, "REJECTS AppendEntries from S" + ae.leaderId()
                    + ": leader assumed I have (idx " + ae.prevLogIndex() + ", term " + ae.prevLogTerm()
                    + "); my log ends at index " + lastLogIndex());
            cluster.send(id, ae.leaderId(), new Msg.AppendReply(currentTerm, false, 0, id));
            return;
        }

        int idx = Math.min(ae.prevLogIndex(), lastLogIndex());
        for (Entry e : ae.entries()) {
            idx++;
            if (idx <= lastLogIndex()) {
                if (log.get(idx).term() != e.term()) {
                    cluster.event(id, "deletes its own entries from index " + idx
                            + " (had " + log.get(idx) + ", leader says " + e + ")");
                    while (lastLogIndex() >= idx) log.remove(log.size() - 1);  // followers DO delete
                    log.add(e);
                }
            } else {
                log.add(e);
            }
        }
        if (ae.leaderCommit() > commitIndex) commitIndex = Math.min(ae.leaderCommit(), lastLogIndex());
        // "I now agree with you up to here." When the prefix check above is ON this
        // is always exactly `idx`; the follower can only say it because it checked.
        cluster.send(id, ae.leaderId(),
                new Msg.AppendReply(currentTerm, true, ae.prevLogIndex() + ae.entries().size(), id));
    }

    void onAppendReply(Msg.AppendReply r) {
        if (r.term() > currentTerm) { stepDown(r.term(), "a follower knew a higher term"); return; }
        if (role != Role.LEADER || r.term() != currentTerm) return;
        if (r.success()) {
            matchIndex[r.followerId()] = Math.max(matchIndex[r.followerId()], r.matchIndex());
            nextIndex[r.followerId()] = matchIndex[r.followerId()] + 1;
            advanceCommit();
        } else {
            // Back up one and try again. The follower will keep rejecting until we
            // find the last index where the two logs agree.
            nextIndex[r.followerId()] = Math.max(1, nextIndex[r.followerId()] - 1);
            sendAppend(r.followerId());
        }
    }

    void advanceCommit() {
        for (int n = lastLogIndex(); n > commitIndex; n--) {
            int count = 0;
            for (int p : peers) if (matchIndex[p] >= n) count++;
            // INVARIANT (State Machine Safety / Figure 8): a leader may only mark an
            // entry committed when the entry is from ITS OWN term. Counting replicas
            // of an old-term entry is not enough -- that entry can still be erased.
            boolean ownTerm = bugs.commitAnyTerm || log.get(n).term() == currentTerm;
            if (count >= majority() && ownTerm) {
                cluster.event(id, "COMMITS up to index " + n + " (" + count + "/" + peers.length
                        + " servers have it; entry term " + log.get(n).term() + " == my term " + currentTerm + ")");
                commitIndex = n;
                return;
            }
        }
    }

    void applyCommitted() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            applied.add(log.get(lastApplied).cmd());
        }
    }

    // ================= plumbing =================
    void receive(Msg m, int from) {
        if (!alive) return;
        switch (m) {
            case Msg.RequestVote rv -> onRequestVote(rv);
            case Msg.VoteReply r    -> onVoteReply(r);
            case Msg.AppendEntries a-> onAppendEntries(a);
            case Msg.AppendReply r  -> onAppendReply(r);
            default -> throw new IllegalStateException("unknown message " + m);
        }
    }

    String logString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 1; i < log.size(); i++) { if (i > 1) sb.append(' '); sb.append(log.get(i)); }
        return sb.append(']').toString();
    }

    @Override public String toString() {
        return "S" + id + " " + role + " term=" + currentTerm + " commit=" + commitIndex + " log=" + logString();
    }
}
