import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Two-phase commit (2PC) — single-process learning lab.
 *
 * <p>The story: move Rs.500 from alice's account at BANK A to bob's account at
 * BANK B. Debit and credit MUST both happen or neither — and the two banks
 * are separate systems. 2PC makes the two commits atomic by adding a
 * COORDINATOR and splitting commit into two phases:
 *
 * <pre>
 *   Phase 1 (voting):   coordinator asks every participant "PREPARE — could
 *                       you commit this if asked?" Each participant either
 *                       votes YES (after durably promising it can commit OR
 *                       undo, and locking the touched rows) or votes NO.
 *   Phase 2 (decision): if EVERY vote was YES, the coordinator durably logs
 *                       COMMIT — that log write IS the commit — then tells
 *                       everyone. Any NO (or timeout) → global ABORT.
 * </pre>
 *
 * What is REAL here:      the protocol logic — durable logs on every node,
 *                         the vote rules, locks, timeouts, recovery from logs,
 *                         the in-doubt (blocked) state.
 * <br>
 * What is SIMULATED here: the machines and network (objects and method calls),
 *                         crashes (exceptions at chosen instants; restart =
 *                         a new object handed the old durable log).
 *
 * <p>Run the guided experiments:
 * <pre>
 *   java TpcLab.java happy-path
 *   java TpcLab.java participant-votes-no
 *   java TpcLab.java participant-crash-before-vote
 *   java TpcLab.java coordinator-crash-blocks-everyone
 *   java TpcLab.java coordinator-recovery
 *   java TpcLab.java two-possible-worlds
 *   java TpcLab.java all
 * </pre>
 */
public class TpcLab {

    /** A process dies at an exact instant. */
    static class Crash extends RuntimeException {
        Crash(String message) { super(message); }
    }

    /** An operation cannot proceed because a lock is held. */
    static class Blocked extends RuntimeException {
        Blocked(String message) { super(message); }
    }

    /**
     * One record in a durable, append-only write-ahead log. A written record is
     * never edited: recovery on every node is nothing but re-reading these.
     * {@code account}/{@code delta} carry the undo+redo info of a PREPARED
     * record; {@code note} is the human-readable payload of a BEGIN record.
     */
    record LogEntry(String txn, String kind, String account, int delta, String note) {
        static LogEntry of(String txn, String kind) { return new LogEntry(txn, kind, null, 0, null); }
        static LogEntry prepared(String txn, String account, int delta) {
            return new LogEntry(txn, "PREPARED", account, delta, null);
        }
        static LogEntry begin(String txn, String note) { return new LogEntry(txn, "BEGIN", null, 0, note); }
    }

    /** One participant's slice of a transaction: (bank, account, delta). */
    record Op(Bank bank, String account, int delta) {}

    /** The change a bank has promised but not yet applied: (account, delta). */
    record Pending(String account, int delta) {}

    /** What a restarted bank hands back: itself, plus the txns it is in doubt about. */
    record Recovered(Bank bank, List<String> inDoubt) {}

    // -----------------------------------------------------------------------
    // Participant: a bank. Own accounts, own locks, own durable log.
    // -----------------------------------------------------------------------
    static class Bank {
        final String name;
        final Map<String, Integer> accounts;          // account -> balance (the database)
        final List<LogEntry> log;
        final Map<String, String> locks = new LinkedHashMap<>();    // account -> txn id holding it
        final Map<String, Pending> pending = new LinkedHashMap<>(); // txn -> (account, delta)
        final Map<String, String> state = new LinkedHashMap<>();    // txn -> PREPARED/COMMITTED/ABORTED
        boolean crashBeforeVote = false;
        boolean crashAfterVote = false;
        boolean voteNo = false;
        boolean down = false;                          // a crashed bank is unreachable

        Bank(String name, Map<String, Integer> accounts) {
            this(name, accounts, null);
        }

        Bank(String name, Map<String, Integer> accounts, List<LogEntry> durableLog) {
            this.name = name;
            this.accounts = new LinkedHashMap<>(accounts);
            this.log = durableLog != null ? durableLog : new ArrayList<>();
        }

        // --- phase 1 -------------------------------------------------------
        /**
         * Return "YES" or "NO". Voting YES is a durable promise: after it,
         * this bank can no longer change its mind — it must be able to commit
         * AND to abort, and must wait for the coordinator's decision.
         */
        String prepare(String txn, String account, int delta) {
            if (crashBeforeVote) {
                crashBeforeVote = false;
                down = true;
                throw new Crash(name + " died before voting");
            }
            if (voteNo || accounts.getOrDefault(account, 0) + delta < 0) {
                log.add(LogEntry.of(txn, "VOTED-NO"));
                return "NO";
            }
            locks.put(account, txn);                      // nobody else may touch it
            pending.put(txn, new Pending(account, delta));
            log.add(LogEntry.prepared(txn, account, delta));  // durable FIRST
            state.put(txn, "PREPARED");
            if (crashAfterVote) {
                crashAfterVote = false;
                down = true;   // PREPARED is durably logged; the YES is lost
                throw new Crash(name + " died right after voting YES");
            }
            return "YES";
        }

        // --- phase 2 -------------------------------------------------------
        String commit(String txn) {
            if (down) throw new Crash(name + " is down — message lost");
            if ("COMMITTED".equals(state.get(txn))) return "already committed (idempotent)";
            Pending p = pending.get(txn);
            accounts.put(p.account(), accounts.get(p.account()) + p.delta());
            log.add(LogEntry.of(txn, "COMMITTED"));
            state.put(txn, "COMMITTED");
            locks.remove(p.account());
            return "committed: " + p.account() + " now " + accounts.get(p.account());
        }

        String abort(String txn) {
            if (down) throw new Crash(name + " is down — message lost");
            Pending p = pending.remove(txn);
            if (p != null) locks.remove(p.account());
            log.add(LogEntry.of(txn, "ABORTED"));
            state.put(txn, "ABORTED");
            return "aborted: nothing changed";
        }

        // --- what everyone else experiences --------------------------------
        /** Another customer at the counter. Locked row -> they must WAIT. */
        int readBalance(String account) {
            if (locks.containsKey(account)) {
                throw new Blocked(name + ": account '" + account + "' is locked by in-flight transaction "
                        + locks.get(account) + " — come back later");
            }
            return accounts.get(account);
        }

        // --- crash recovery -------------------------------------------------
        /**
         * A fresh process starts from the durable log. The one interesting
         * case: a txn with PREPARED but no outcome — this bank promised YES
         * and cannot decide alone. It is IN DOUBT: relock and wait/ask.
         */
        static Recovered recover(String name, Map<String, Integer> accountsAtBoot, List<LogEntry> durableLog) {
            Bank bank = new Bank(name, accountsAtBoot, new ArrayList<>(durableLog));
            List<String> inDoubt = new ArrayList<>();
            for (LogEntry entry : durableLog) {
                String txn = entry.txn(), kind = entry.kind();
                if (kind.equals("PREPARED")) {
                    bank.pending.put(txn, new Pending(entry.account(), entry.delta()));
                    bank.state.put(txn, "PREPARED");
                    bank.locks.put(entry.account(), txn);     // relock: promise stands
                } else if (kind.equals("COMMITTED") || kind.equals("ABORTED")) {
                    bank.state.put(txn, kind);
                    Pending p = bank.pending.remove(txn);
                    if (p != null) bank.locks.remove(p.account());
                }
            }
            for (Map.Entry<String, String> e : bank.state.entrySet()) {
                if (e.getValue().equals("PREPARED")) inDoubt.add(e.getKey());
            }
            return new Recovered(bank, inDoubt);
        }

        /** Resolve an in-doubt txn by asking the (recovered) coordinator. */
        String askCoordinator(Coordinator coordinator, String txn) {
            String decision = coordinator.decisionFor(txn);
            if (decision.equals("COMMIT")) return commit(txn);
            return abort(txn);
        }
    }

    // -----------------------------------------------------------------------
    // Coordinator. Its durable log write of the decision IS the commit point.
    // -----------------------------------------------------------------------
    static class Coordinator {
        final List<LogEntry> log;
        final boolean narrate;
        boolean crashBeforeDecisionLogged = false;
        boolean crashAfterDecisionLogged = false;

        Coordinator() { this(null, true); }

        Coordinator(boolean narrate) { this(null, narrate); }

        Coordinator(List<LogEntry> durableLog, boolean narrate) {
            this.log = durableLog != null ? durableLog : new ArrayList<>();
            this.narrate = narrate;
        }

        private void say(String msg) {
            if (narrate) System.out.println("    " + msg);
        }

        /** ops: list of (bank, account, delta). Returns COMMITTED/ABORTED. */
        String runTransfer(String txn, List<Op> ops) {
            log.add(LogEntry.begin(txn, describe(ops)));
            // ---- phase 1: collect votes -------------------------------------
            List<String> votes = new ArrayList<>();
            for (Op op : ops) {
                try {
                    String vote = op.bank().prepare(txn, op.account(), op.delta());
                    say(op.bank().name + " votes " + vote + " on " + txn);
                    votes.add(vote);
                } catch (Crash e) {
                    say(op.bank().name + " did not answer (" + e.getMessage() + ") — TIMEOUT counts as NO");
                    votes.add("TIMEOUT");
                }
            }
            boolean allYes = votes.stream().allMatch(v -> v.equals("YES"));

            if (crashBeforeDecisionLogged) {
                crashBeforeDecisionLogged = false;
                throw new Crash("coordinator died BEFORE logging a decision "
                        + "(votes were: " + votes + ")");
            }

            // ---- the commit point -------------------------------------------
            String decision = allYes ? "COMMIT" : "ABORT";
            log.add(LogEntry.of(txn, decision));        // THE moment of truth
            say("coordinator durably logs decision: " + decision + "  <-- commit point");

            if (crashAfterDecisionLogged) {
                crashAfterDecisionLogged = false;
                throw new Crash("coordinator died AFTER logging " + decision + ", before telling "
                        + "the banks");
            }

            // ---- phase 2: tell everyone -------------------------------------
            notifyParticipants(txn, ops, decision);
            return decision.equals("COMMIT") ? "COMMITTED" : "ABORTED";
        }

        void notifyParticipants(String txn, List<Op> ops, String decision) {
            // Real coordinators retry phase 2 until every participant ACKs, and
            // only then log END. Here: unreachable banks resolve later, by
            // asking the coordinator when they recover (Bank.askCoordinator).
            for (Op op : ops) {
                Bank bank = op.bank();
                try {
                    if (decision.equals("COMMIT")) {
                        say(bank.name + " <- COMMIT: " + bank.commit(txn));
                    } else {
                        say(bank.name + " <- ABORT: " + bank.abort(txn));
                    }
                } catch (Crash e) {
                    say(bank.name + " unreachable (" + e.getMessage() + ") — it will learn the "
                            + "decision when it recovers and asks");
                }
            }
            log.add(LogEntry.of(txn, "END"));
        }

        /**
         * What a recovered coordinator (or an asking participant) learns
         * from the durable log. No decision logged => it is SAFE to abort,
         * because no participant can have committed. ('Presumed abort'.)
         */
        String decisionFor(String txn) {
            for (LogEntry entry : log) {
                if (entry.txn().equals(txn) && (entry.kind().equals("COMMIT") || entry.kind().equals("ABORT"))) {
                    return entry.kind();
                }
            }
            return "ABORT";
        }

        /** Restart from log; finish any txn with a decision but no END. */
        static Coordinator recover(List<LogEntry> durableLog, Map<String, List<Op>> banksOps) {
            Coordinator c = new Coordinator(new ArrayList<>(durableLog), true);
            for (Map.Entry<String, List<Op>> e : banksOps.entrySet()) {
                String txn = e.getKey();
                List<Op> ops = e.getValue();
                String decision = c.decisionFor(txn);
                boolean done = c.log.stream().anyMatch(x -> x.txn().equals(txn) && x.kind().equals("END"));
                if (!done) {
                    c.say("recovered coordinator: " + txn + " has decision " + decision + " but no "
                            + "END — re-sending phase 2");
                    c.notifyParticipants(txn, ops, decision);
                }
            }
            return c;
        }

        /** Only the BEGIN record's payload — never read back, just auditable. */
        private static String describe(List<Op> ops) {
            List<String> parts = new ArrayList<>();
            for (Op op : ops) parts.add("(" + op.bank().name + ", " + op.account() + ", " + op.delta() + ")");
            return parts.toString();
        }
    }

    // -----------------------------------------------------------------------
    // Guided experiments
    // -----------------------------------------------------------------------
    static Bank[] freshBanks() {
        Bank a = new Bank("BANK-A", Map.of("alice", 1000));
        Bank b = new Bank("BANK-B", Map.of("bob", 200));
        return new Bank[] {a, b};
    }

    static List<Op> transferOps(Bank a, Bank b) {
        return transferOps(a, b, 500);
    }

    static List<Op> transferOps(Bank a, Bank b, int amount) {
        return List.of(new Op(a, "alice", -amount), new Op(b, "bob", +amount));
    }

    static void show(Bank a, Bank b) {
        for (Bank bank : new Bank[] {a, b}) {
            System.out.printf("    %-6s accounts=%s locks=%s%n",
                    bank.name, bank.accounts, new ArrayList<>(bank.locks.keySet()));
        }
    }

    static void tryRead(Bank bank, String account) {
        try {
            System.out.printf("    another customer reads %s at %s -> %d%n",
                    account, bank.name, bank.readBalance(account));
        } catch (Blocked e) {
            System.out.printf("    another customer reads %s at %s -> BLOCKED (%s)%n",
                    account, bank.name, e.getMessage());
        }
    }

    static void experimentHappyPath() {
        System.out.println("=".repeat(72));
        System.out.println("EXPERIMENT 1: happy path — Rs.500 from alice (A) to bob (B)");
        System.out.println("=".repeat(72));
        Bank[] banks = freshBanks();
        Bank a = banks[0], b = banks[1];
        String result = new Coordinator().runTransfer("txn-1", transferOps(a, b));
        System.out.println("  outcome: " + result);
        show(a, b);
        System.out.println();
        System.out.println("VERDICT: both banks moved money in the same transaction, or");
        System.out.println("neither would have. The price paid even here: two round trips,");
        System.out.println("two log flushes per bank, and locks held from vote to decision.");
        System.out.println();
    }

    static void experimentVotesNo() {
        System.out.println("=".repeat(72));
        System.out.println("EXPERIMENT 2: bank B votes NO — everyone aborts");
        System.out.println("=".repeat(72));
        Bank[] banks = freshBanks();
        Bank a = banks[0], b = banks[1];
        b.voteNo = true;
        String result = new Coordinator().runTransfer("txn-2", transferOps(a, b));
        System.out.println("  outcome: " + result);
        show(a, b);
        System.out.println();
        System.out.println("VERDICT: one NO vetoes the whole thing. alice's debit was");
        System.out.println("prepared and locked — the abort released it, balance untouched.");
        System.out.println("2PC is unanimous consent, not majority vote.");
        System.out.println();
    }

    static void experimentParticipantCrash() {
        System.out.println("=".repeat(72));
        System.out.println("EXPERIMENT 3: bank B crashes before voting — timeout = NO");
        System.out.println("=".repeat(72));
        Bank[] banks = freshBanks();
        Bank a = banks[0], b = banks[1];
        b.crashBeforeVote = true;
        String result = new Coordinator().runTransfer("txn-3", transferOps(a, b));
        System.out.println("  outcome: " + result);
        show(a, b);
        System.out.println();
        System.out.println("VERDICT: a silent participant is treated as a NO — this timeout");
        System.out.println("is SAFE because no decision existed yet. Compare with the next");
        System.out.println("experiment, where a timeout can NOT be resolved this way.");
        System.out.println();
    }

    static void experimentCoordinatorCrash() {
        System.out.println("=".repeat(72));
        System.out.println("EXPERIMENT 4: the famous one — coordinator dies after the votes");
        System.out.println("=".repeat(72));
        Bank[] banks = freshBanks();
        Bank a = banks[0], b = banks[1];
        Coordinator c = new Coordinator();
        c.crashAfterDecisionLogged = true;
        System.out.println("Both banks vote YES; the coordinator logs COMMIT... and dies");
        System.out.println("before telling anyone:");
        try {
            c.runTransfer("txn-4", transferOps(a, b));
        } catch (Crash e) {
            System.out.println("    CRASH: " + e.getMessage());
        }
        System.out.println("Both banks are now IN DOUBT — they promised YES, so they hold");
        System.out.println("their locks and CANNOT decide alone:");
        show(a, b);
        tryRead(a, "alice");
        System.out.println();
        System.out.println("Why can't bank A just abort? Because the coordinator's log says");
        System.out.println("COMMIT — bank B might learn that and commit. A would then have");
        System.out.println("aborted a committed transaction: money destroyed. Why not just");
        System.out.println("commit? In another run the log might say ABORT. A prepared");
        System.out.println("participant literally cannot tell those worlds apart (see");
        System.out.println("experiment 6). So it BLOCKS: locks held, customers waiting,");
        System.out.println("until the coordinator returns. This is 2PC's defining weakness.");
        System.out.println();
    }

    static void experimentCoordinatorRecovery() {
        System.out.println("=".repeat(72));
        System.out.println("EXPERIMENT 5: the coordinator restarts and finishes the job");
        System.out.println("=".repeat(72));
        Bank[] banks = freshBanks();
        Bank a = banks[0], b = banks[1];
        Coordinator c = new Coordinator();
        c.crashAfterDecisionLogged = true;
        try {
            c.runTransfer("txn-5", transferOps(a, b));
        } catch (Crash e) {
            System.out.println("    CRASH: " + e.getMessage());
        }
        System.out.println("While it is down, a customer hits the locked row:");
        tryRead(a, "alice");
        System.out.println("Coordinator restarts, reads its durable log, re-sends phase 2:");
        Coordinator.recover(c.log, Map.of("txn-5", transferOps(a, b)));
        show(a, b);
        tryRead(a, "alice");
        System.out.println();
        System.out.println("VERDICT: recovery is pure log-replay. Because the decision was");
        System.out.println("logged BEFORE the crash, the transaction was already committed —");
        System.out.println("the banks just hadn't been told. Blocking ended only because the");
        System.out.println("coordinator came back; nothing else could have ended it.");
        System.out.println();
    }

    static void experimentTwoWorlds() {
        System.out.println("=".repeat(72));
        System.out.println("EXPERIMENT 6: two possible worlds — why a prepared bank is stuck");
        System.out.println("=".repeat(72));
        System.out.println("Two runs. In BOTH, bank A sees exactly the same thing: it votes");
        System.out.println("YES, then silence. Watch what the two coordinators' logs say.");
        Map<String, String> decisions = new LinkedHashMap<>();
        Map<String, Integer> balances = new LinkedHashMap<>();
        String[][] worlds = {{"WORLD 1", "after"}, {"WORLD 2", "before"}};
        for (String[] w : worlds) {
            String world = w[0], crashKnob = w[1];
            Bank[] banks = freshBanks();
            Bank a = banks[0], b = banks[1];
            Coordinator c = new Coordinator(false);
            if (crashKnob.equals("after")) {
                c.crashAfterDecisionLogged = true;       // decision: COMMIT
            } else {
                c.crashBeforeDecisionLogged = true;      // no decision logged
            }
            try {
                c.runTransfer("txn-6", transferOps(a, b));
            } catch (Crash ignored) {
                // the crash is the point of the experiment
            }
            System.out.printf("  %s: bank A state=%s, locks=%s  (identical in both worlds)%n",
                    world, a.state.get("txn-6"), new ArrayList<>(a.locks.keySet()));
            String decision = c.decisionFor("txn-6");
            Coordinator.recover(c.log, Map.of("txn-6", transferOps(a, b)));
            decisions.put(world, decision);
            balances.put(world, a.accounts.get("alice"));
            System.out.printf("  %s: coordinator log says %s -> alice ends with %d%n",
                    world, decision, a.accounts.get("alice"));
        }
        System.out.println();
        System.out.println("VERDICT: same participant-visible history, opposite outcomes:");
        System.out.printf("  WORLD 1 -> %s, alice %d   WORLD 2 -> %s, alice %d%n",
                decisions.get("WORLD 1"), balances.get("WORLD 1"),
                decisions.get("WORLD 2"), balances.get("WORLD 2"));
        System.out.println("No local rule lets a prepared participant pick the right answer —");
        System.out.println("the answer lives ONLY in the coordinator's log. That is why it");
        System.out.println("must block, and why 2PC availability hangs on one machine.");
        System.out.println();
    }

    static final Map<String, Runnable> EXPERIMENTS = new LinkedHashMap<>();
    static {
        EXPERIMENTS.put("happy-path", TpcLab::experimentHappyPath);
        EXPERIMENTS.put("participant-votes-no", TpcLab::experimentVotesNo);
        EXPERIMENTS.put("participant-crash-before-vote", TpcLab::experimentParticipantCrash);
        EXPERIMENTS.put("coordinator-crash-blocks-everyone", TpcLab::experimentCoordinatorCrash);
        EXPERIMENTS.put("coordinator-recovery", TpcLab::experimentCoordinatorRecovery);
        EXPERIMENTS.put("two-possible-worlds", TpcLab::experimentTwoWorlds);
    }

    public static void main(String[] args) {
        String choice = args.length > 0 ? args[0] : "all";
        if (choice.equals("all")) {
            for (Runnable fn : EXPERIMENTS.values()) fn.run();
        } else if (EXPERIMENTS.containsKey(choice)) {
            EXPERIMENTS.get(choice).run();
        } else {
            System.out.println("usage: java TpcLab.java [" + String.join("|", EXPERIMENTS.keySet()) + "|all]");
            System.exit(1);
        }
    }
}
