#!/usr/bin/env python3
"""Two-phase commit (2PC) — single-process learning lab.

The story: move Rs.500 from alice's account at BANK A to bob's account at
BANK B. Debit and credit MUST both happen or neither — and the two banks
are separate systems. 2PC makes the two commits atomic by adding a
COORDINATOR and splitting commit into two phases:

  Phase 1 (voting):   coordinator asks every participant "PREPARE — could
                      you commit this if asked?" Each participant either
                      votes YES (after durably promising it can commit OR
                      undo, and locking the touched rows) or votes NO.
  Phase 2 (decision): if EVERY vote was YES, the coordinator durably logs
                      COMMIT — that log write IS the commit — then tells
                      everyone. Any NO (or timeout) → global ABORT.

What is REAL here:      the protocol logic — durable logs on every node,
                        the vote rules, locks, timeouts, recovery from logs,
                        the in-doubt (blocked) state.
What is SIMULATED here: the machines and network (objects and method calls),
                        crashes (exceptions at chosen instants; restart =
                        a new object handed the old durable log).

Run the guided experiments:
  python3 tpc_lab.py happy-path
  python3 tpc_lab.py participant-votes-no
  python3 tpc_lab.py participant-crash-before-vote
  python3 tpc_lab.py coordinator-crash-blocks-everyone
  python3 tpc_lab.py coordinator-recovery
  python3 tpc_lab.py two-possible-worlds
  python3 tpc_lab.py all
"""

import sys


class Crash(Exception):
    """A process dies at an exact instant."""


class Blocked(Exception):
    """An operation cannot proceed because a lock is held."""


# ---------------------------------------------------------------------------
# Participant: a bank. Own accounts, own locks, own durable log.
# ---------------------------------------------------------------------------
class Bank:
    def __init__(self, name, accounts, durable_log=None):
        self.name = name
        self.accounts = dict(accounts)     # account -> balance (the database)
        self.log = durable_log if durable_log is not None else []
        self.locks = {}                    # account -> txn id holding it
        self.pending = {}                  # txn -> (account, delta)
        self.state = {}                    # txn -> PREPARED/COMMITTED/ABORTED
        self.crash_before_vote = False
        self.crash_after_vote = False
        self.vote_no = False
        self.down = False              # a crashed bank is unreachable

    # --- phase 1 ---------------------------------------------------------
    def prepare(self, txn, account, delta):
        """Return "YES" or "NO". Voting YES is a durable promise: after it,
        this bank can no longer change its mind — it must be able to commit
        AND to abort, and must wait for the coordinator's decision."""
        if self.crash_before_vote:
            self.crash_before_vote = False
            self.down = True
            raise Crash("%s died before voting" % self.name)
        if self.vote_no or self.accounts.get(account, 0) + delta < 0:
            self.log.append((txn, "VOTED-NO"))
            return "NO"
        self.locks[account] = txn                   # nobody else may touch it
        self.pending[txn] = (account, delta)
        self.log.append((txn, "PREPARED", account, delta))  # durable FIRST
        self.state[txn] = "PREPARED"
        if self.crash_after_vote:
            self.crash_after_vote = False
            self.down = True   # PREPARED is durably logged; the YES is lost
            raise Crash("%s died right after voting YES" % self.name)
        return "YES"

    # --- phase 2 ---------------------------------------------------------
    def commit(self, txn):
        if self.down:
            raise Crash("%s is down — message lost" % self.name)
        if self.state.get(txn) == "COMMITTED":
            return "already committed (idempotent)"
        account, delta = self.pending[txn]
        self.accounts[account] += delta
        self.log.append((txn, "COMMITTED"))
        self.state[txn] = "COMMITTED"
        self.locks.pop(account, None)
        return "committed: %s now %d" % (account, self.accounts[account])

    def abort(self, txn):
        if self.down:
            raise Crash("%s is down — message lost" % self.name)
        if txn in self.pending:
            account, _ = self.pending.pop(txn)
            self.locks.pop(account, None)
        self.log.append((txn, "ABORTED"))
        self.state[txn] = "ABORTED"
        return "aborted: nothing changed"

    # --- what everyone else experiences ----------------------------------
    def read_balance(self, account):
        """Another customer at the counter. Locked row -> they must WAIT."""
        if account in self.locks:
            raise Blocked(
                "%s: account '%s' is locked by in-flight transaction %s — "
                "come back later" % (self.name, account, self.locks[account])
            )
        return self.accounts[account]

    # --- crash recovery ---------------------------------------------------
    @staticmethod
    def recover(name, accounts_at_boot, durable_log):
        """A fresh process starts from the durable log. The one interesting
        case: a txn with PREPARED but no outcome — this bank promised YES
        and cannot decide alone. It is IN DOUBT: relock and wait/ask."""
        bank = Bank(name, accounts_at_boot, durable_log=list(durable_log))
        in_doubt = []
        for entry in durable_log:
            txn, kind = entry[0], entry[1]
            if kind == "PREPARED":
                bank.pending[txn] = (entry[2], entry[3])
                bank.state[txn] = "PREPARED"
                bank.locks[entry[2]] = txn          # relock: promise stands
            elif kind in ("COMMITTED", "ABORTED"):
                bank.state[txn] = kind
                acct = bank.pending.pop(txn, (None,))[0]
                if acct:
                    bank.locks.pop(acct, None)
        for txn, st in bank.state.items():
            if st == "PREPARED":
                in_doubt.append(txn)
        return bank, in_doubt

    def ask_coordinator(self, coordinator, txn):
        """Resolve an in-doubt txn by asking the (recovered) coordinator."""
        decision = coordinator.decision_for(txn)
        if decision == "COMMIT":
            return self.commit(txn)
        return self.abort(txn)


# ---------------------------------------------------------------------------
# Coordinator. Its durable log write of the decision IS the commit point.
# ---------------------------------------------------------------------------
class Coordinator:
    def __init__(self, durable_log=None, narrate=True):
        self.log = durable_log if durable_log is not None else []
        self.narrate = narrate
        self.crash_before_decision_logged = False
        self.crash_after_decision_logged = False

    def _say(self, msg):
        if self.narrate:
            print("    " + msg)

    def run_transfer(self, txn, ops):
        """ops: list of (bank, account, delta). Returns COMMITTED/ABORTED."""
        self.log.append((txn, "BEGIN", [(b.name, a, d) for b, a, d in ops]))
        # ---- phase 1: collect votes -------------------------------------
        votes = []
        for bank, account, delta in ops:
            try:
                vote = bank.prepare(txn, account, delta)
                self._say("%s votes %s on %s" % (bank.name, vote, txn))
                votes.append(vote)
            except Crash as e:
                self._say("%s did not answer (%s) — TIMEOUT counts as NO"
                          % (bank.name, e))
                votes.append("TIMEOUT")
        all_yes = all(v == "YES" for v in votes)

        if self.crash_before_decision_logged:
            self.crash_before_decision_logged = False
            raise Crash("coordinator died BEFORE logging a decision "
                        "(votes were: %s)" % votes)

        # ---- the commit point -------------------------------------------
        decision = "COMMIT" if all_yes else "ABORT"
        self.log.append((txn, decision))            # THE moment of truth
        self._say("coordinator durably logs decision: %s  <-- commit point"
                  % decision)

        if self.crash_after_decision_logged:
            self.crash_after_decision_logged = False
            raise Crash("coordinator died AFTER logging %s, before telling "
                        "the banks" % decision)

        # ---- phase 2: tell everyone -------------------------------------
        self._notify(txn, ops, decision)
        return "COMMITTED" if decision == "COMMIT" else "ABORTED"

    def _notify(self, txn, ops, decision):
        # Real coordinators retry phase 2 until every participant ACKs, and
        # only then log END. Here: unreachable banks resolve later, by
        # asking the coordinator when they recover (Bank.ask_coordinator).
        for bank, _, _ in ops:
            try:
                if decision == "COMMIT":
                    self._say("%s <- COMMIT: %s"
                              % (bank.name, bank.commit(txn)))
                else:
                    self._say("%s <- ABORT: %s"
                              % (bank.name, bank.abort(txn)))
            except Crash as e:
                self._say("%s unreachable (%s) — it will learn the "
                          "decision when it recovers and asks"
                          % (bank.name, e))
        self.log.append((txn, "END"))

    def decision_for(self, txn):
        """What a recovered coordinator (or an asking participant) learns
        from the durable log. No decision logged => it is SAFE to abort,
        because no participant can have committed. ('Presumed abort'.)"""
        for entry in self.log:
            if entry[0] == txn and entry[1] in ("COMMIT", "ABORT"):
                return entry[1]
        return "ABORT"

    @staticmethod
    def recover(durable_log, banks_ops):
        """Restart from log; finish any txn with a decision but no END."""
        c = Coordinator(durable_log=list(durable_log))
        for txn, ops in banks_ops.items():
            decision = c.decision_for(txn)
            done = any(e[0] == txn and e[1] == "END" for e in c.log)
            if not done:
                c._say("recovered coordinator: %s has decision %s but no "
                       "END — re-sending phase 2" % (txn, decision))
                c._notify(txn, ops, decision)
        return c


# ---------------------------------------------------------------------------
# Guided experiments
# ---------------------------------------------------------------------------
def fresh_banks():
    a = Bank("BANK-A", {"alice": 1000})
    b = Bank("BANK-B", {"bob": 200})
    return a, b


def transfer_ops(a, b, amount=500):
    return [(a, "alice", -amount), (b, "bob", +amount)]


def show(a, b):
    for bank in (a, b):
        print("    %-6s accounts=%s locks=%s"
              % (bank.name, bank.accounts, list(bank.locks) or "[]"))


def try_read(bank, account):
    try:
        print("    another customer reads %s at %s -> %d"
              % (account, bank.name, bank.read_balance(account)))
    except Blocked as e:
        print("    another customer reads %s at %s -> BLOCKED (%s)"
              % (account, bank.name, e))


def experiment_happy_path():
    print("=" * 72)
    print("EXPERIMENT 1: happy path — Rs.500 from alice (A) to bob (B)")
    print("=" * 72)
    a, b = fresh_banks()
    result = Coordinator().run_transfer("txn-1", transfer_ops(a, b))
    print("  outcome: %s" % result)
    show(a, b)
    print()
    print("VERDICT: both banks moved money in the same transaction, or")
    print("neither would have. The price paid even here: two round trips,")
    print("two log flushes per bank, and locks held from vote to decision.")
    print()


def experiment_votes_no():
    print("=" * 72)
    print("EXPERIMENT 2: bank B votes NO — everyone aborts")
    print("=" * 72)
    a, b = fresh_banks()
    b.vote_no = True
    result = Coordinator().run_transfer("txn-2", transfer_ops(a, b))
    print("  outcome: %s" % result)
    show(a, b)
    print()
    print("VERDICT: one NO vetoes the whole thing. alice's debit was")
    print("prepared and locked — the abort released it, balance untouched.")
    print("2PC is unanimous consent, not majority vote.")
    print()


def experiment_participant_crash():
    print("=" * 72)
    print("EXPERIMENT 3: bank B crashes before voting — timeout = NO")
    print("=" * 72)
    a, b = fresh_banks()
    b.crash_before_vote = True
    result = Coordinator().run_transfer("txn-3", transfer_ops(a, b))
    print("  outcome: %s" % result)
    show(a, b)
    print()
    print("VERDICT: a silent participant is treated as a NO — this timeout")
    print("is SAFE because no decision existed yet. Compare with the next")
    print("experiment, where a timeout can NOT be resolved this way.")
    print()


def experiment_coordinator_crash():
    print("=" * 72)
    print("EXPERIMENT 4: the famous one — coordinator dies after the votes")
    print("=" * 72)
    a, b = fresh_banks()
    c = Coordinator()
    c.crash_after_decision_logged = True
    print("Both banks vote YES; the coordinator logs COMMIT... and dies")
    print("before telling anyone:")
    try:
        c.run_transfer("txn-4", transfer_ops(a, b))
    except Crash as e:
        print("    CRASH: %s" % e)
    print("Both banks are now IN DOUBT — they promised YES, so they hold")
    print("their locks and CANNOT decide alone:")
    show(a, b)
    try_read(a, "alice")
    print()
    print("Why can't bank A just abort? Because the coordinator's log says")
    print("COMMIT — bank B might learn that and commit. A would then have")
    print("aborted a committed transaction: money destroyed. Why not just")
    print("commit? In another run the log might say ABORT. A prepared")
    print("participant literally cannot tell those worlds apart (see")
    print("experiment 6). So it BLOCKS: locks held, customers waiting,")
    print("until the coordinator returns. This is 2PC's defining weakness.")
    print()


def experiment_coordinator_recovery():
    print("=" * 72)
    print("EXPERIMENT 5: the coordinator restarts and finishes the job")
    print("=" * 72)
    a, b = fresh_banks()
    c = Coordinator()
    c.crash_after_decision_logged = True
    try:
        c.run_transfer("txn-5", transfer_ops(a, b))
    except Crash as e:
        print("    CRASH: %s" % e)
    print("While it is down, a customer hits the locked row:")
    try_read(a, "alice")
    print("Coordinator restarts, reads its durable log, re-sends phase 2:")
    Coordinator.recover(c.log, {"txn-5": transfer_ops(a, b)})
    show(a, b)
    try_read(a, "alice")
    print()
    print("VERDICT: recovery is pure log-replay. Because the decision was")
    print("logged BEFORE the crash, the transaction was already committed —")
    print("the banks just hadn't been told. Blocking ended only because the")
    print("coordinator came back; nothing else could have ended it.")
    print()


def experiment_two_worlds():
    print("=" * 72)
    print("EXPERIMENT 6: two possible worlds — why a prepared bank is stuck")
    print("=" * 72)
    print("Two runs. In BOTH, bank A sees exactly the same thing: it votes")
    print("YES, then silence. Watch what the two coordinators' logs say.")
    outcomes = {}
    for world, crash_knob in (("WORLD 1", "after"), ("WORLD 2", "before")):
        a, b = fresh_banks()
        c = Coordinator(narrate=False)
        if crash_knob == "after":
            c.crash_after_decision_logged = True     # decision: COMMIT
        else:
            c.crash_before_decision_logged = True    # no decision logged
        try:
            c.run_transfer("txn-6", transfer_ops(a, b))
        except Crash:
            pass
        print("  %s: bank A state=%s, locks=%s  (identical in both worlds)"
              % (world, a.state.get("txn-6"), list(a.locks)))
        decision = c.decision_for("txn-6")
        Coordinator.recover(c.log, {"txn-6": transfer_ops(a, b)})
        outcomes[world] = (decision, a.accounts["alice"])
        print("  %s: coordinator log says %s -> alice ends with %d"
              % (world, decision, a.accounts["alice"]))
    print()
    print("VERDICT: same participant-visible history, opposite outcomes:")
    print("  WORLD 1 -> %s, alice %d   WORLD 2 -> %s, alice %d"
          % (outcomes["WORLD 1"] + outcomes["WORLD 2"]))
    print("No local rule lets a prepared participant pick the right answer —")
    print("the answer lives ONLY in the coordinator's log. That is why it")
    print("must block, and why 2PC availability hangs on one machine.")
    print()


EXPERIMENTS = {
    "happy-path": experiment_happy_path,
    "participant-votes-no": experiment_votes_no,
    "participant-crash-before-vote": experiment_participant_crash,
    "coordinator-crash-blocks-everyone": experiment_coordinator_crash,
    "coordinator-recovery": experiment_coordinator_recovery,
    "two-possible-worlds": experiment_two_worlds,
}

if __name__ == "__main__":
    choice = sys.argv[1] if len(sys.argv) > 1 else "all"
    if choice == "all":
        for fn in EXPERIMENTS.values():
            fn()
    elif choice in EXPERIMENTS:
        EXPERIMENTS[choice]()
    else:
        print("usage: python3 tpc_lab.py [%s|all]" % "|".join(EXPERIMENTS))
        sys.exit(1)
