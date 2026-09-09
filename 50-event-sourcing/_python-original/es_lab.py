"""
Event sourcing learning lab — a bank whose ONLY database is an append-only
event log.

There is no `accounts` table with a `balance` column anywhere in this file.
An account's balance exists only as the result of REPLAYING its events:

    events:  AccountOpened -> MoneyDeposited(100) -> MoneyWithdrawn(30)
    state:   fold(apply, events) == Account(balance=70, version=3)

Everything else in the pattern — optimistic concurrency, time travel,
compensating events, snapshots, upcasting — follows from that one decision.

Run the narrated demo:   python3 es_lab.py
Run the tests:           python3 -m unittest -v test_es
Standard library only.
"""
from __future__ import annotations
import json
import sqlite3
from dataclasses import dataclass, replace


class CommandRejected(Exception):
    """The aggregate said no. Nothing was appended."""


class ConcurrencyConflict(Exception):
    """Someone else appended to this stream since you loaded it. Reload and retry."""


# --------------------------------------------------------------------------
# The event store: streams of immutable events. THE only truth.
# --------------------------------------------------------------------------
class EventStore:
    def __init__(self) -> None:
        self.db = sqlite3.connect(":memory:")
        self.db.execute("""CREATE TABLE events (
            global_seq INTEGER PRIMARY KEY AUTOINCREMENT,
            stream     TEXT NOT NULL,          -- e.g. 'account-7'
            version    INTEGER NOT NULL,       -- 1,2,3,... within the stream
            type       TEXT NOT NULL,
            schema_v   INTEGER NOT NULL DEFAULT 2,
            payload    TEXT NOT NULL,
            UNIQUE (stream, version))""")   # the line optimistic concurrency stands on
        self.db.execute("CREATE TABLE snapshots (stream TEXT PRIMARY KEY, version INTEGER, state TEXT)")
        self.db.commit()

    def append(self, stream: str, expected_version: int, events: list[tuple[str, dict]],
               schema_v: int = 2) -> int:
        """Append events, but ONLY if the stream is still at expected_version.
        This compare-and-append is the entire write-concurrency story."""
        with self.db:
            current = self.stream_version(stream)
            if current != expected_version:
                raise ConcurrencyConflict(
                    f"{stream}: you loaded version {expected_version}, but it is now {current}")
            for i, (type_, payload) in enumerate(events, start=1):
                self.db.execute("INSERT INTO events (stream, version, type, schema_v, payload) VALUES (?,?,?,?,?)",
                                (stream, expected_version + i, type_, schema_v, json.dumps(payload)))
            return expected_version + len(events)

    def read(self, stream: str, from_version: int = 0, to_version: int | None = None
             ) -> list[tuple[int, str, dict]]:
        """Events of one stream, upcast to the current schema on the way out."""
        q = "SELECT version, type, schema_v, payload FROM events WHERE stream=? AND version>?"
        args: list = [stream, from_version]
        if to_version is not None:
            q += " AND version<=?"; args.append(to_version)
        rows = self.db.execute(q + " ORDER BY version", args).fetchall()
        return [(v, t, upcast(t, sv, json.loads(p))) for v, t, sv, p in rows]

    def stream_version(self, stream: str) -> int:
        return self.db.execute("SELECT COALESCE(MAX(version),0) FROM events WHERE stream=?",
                               (stream,)).fetchone()[0]

    # -- snapshots ---------------------------------------------------------
    def save_snapshot(self, stream: str, version: int, state: dict) -> None:
        with self.db:
            self.db.execute("INSERT OR REPLACE INTO snapshots VALUES (?,?,?)",
                            (stream, version, json.dumps(state)))

    def load_snapshot(self, stream: str) -> tuple[int, dict] | None:
        row = self.db.execute("SELECT version, state FROM snapshots WHERE stream=?", (stream,)).fetchone()
        return (row[0], json.loads(row[1])) if row else None


# --------------------------------------------------------------------------
# Schema evolution: events live forever; code changes. Upcasters bridge.
# --------------------------------------------------------------------------
def upcast(type_: str, schema_v: int, payload: dict) -> dict:
    """v1 MoneyDeposited had no 'channel' field (the concept didn't exist).
    Rather than migrating stored history (never!), old events are upgraded
    IN MEMORY as they are read."""
    if type_ in ("MoneyDeposited", "MoneyWithdrawn") and schema_v < 2:
        payload = dict(payload)
        payload.setdefault("channel", "branch")   # every pre-v2 transaction was at a branch
    return payload


# --------------------------------------------------------------------------
# The aggregate: state = fold(apply, events); commands decide, never mutate.
# --------------------------------------------------------------------------
@dataclass(frozen=True)
class Account:
    account_id: str
    owner: str = ""
    balance: float = 0.0
    is_open: bool = False
    version: int = 0

    # -- apply: how each event changes state. NO validation here — the event
    #    already happened; apply must accept it. Pure and total.
    def apply(self, type_: str, e: dict) -> "Account":
        if type_ == "AccountOpened":
            return replace(self, owner=e["owner"], is_open=True, version=self.version + 1)
        if type_ == "MoneyDeposited":
            return replace(self, balance=self.balance + e["amount"], version=self.version + 1)
        if type_ == "MoneyWithdrawn":
            return replace(self, balance=self.balance - e["amount"], version=self.version + 1)
        if type_ == "DepositReversed":                       # the compensating event
            return replace(self, balance=self.balance - e["amount"], version=self.version + 1)
        return replace(self, version=self.version + 1)       # unknown events: tolerated

    @staticmethod
    def load(store: EventStore, account_id: str, *, use_snapshot: bool = False,
             as_of_version: int | None = None) -> tuple["Account", int]:
        """Replay the stream (optionally from a snapshot / up to a past version).
        Returns (state, events_replayed) so the lab can SHOW the replay cost."""
        stream = f"account-{account_id}"
        acct = Account(account_id)
        start = 0
        if use_snapshot and as_of_version is None:
            snap = store.load_snapshot(stream)
            if snap:
                version, state = snap
                acct = Account(account_id, state["owner"], state["balance"], state["is_open"], version)
                start = version
        events = store.read(stream, from_version=start, to_version=as_of_version)
        for _v, type_, payload in events:
            acct = acct.apply(type_, payload)
        return acct, len(events)

    # -- decide: command -> events (or rejection). Validation lives HERE,
    #    against the replayed state. Never mutates; only proposes facts.
    def decide_open(self, owner: str) -> list[tuple[str, dict]]:
        if self.is_open:
            raise CommandRejected("account already open")
        return [("AccountOpened", {"owner": owner})]

    def decide_deposit(self, amount: float, channel: str = "app") -> list[tuple[str, dict]]:
        if not self.is_open:
            raise CommandRejected("no such account")
        if amount <= 0:
            raise CommandRejected("deposit must be positive")
        return [("MoneyDeposited", {"amount": amount, "channel": channel})]

    def decide_withdraw(self, amount: float, channel: str = "app") -> list[tuple[str, dict]]:
        if not self.is_open:
            raise CommandRejected("no such account")
        if amount <= 0:
            raise CommandRejected("withdrawal must be positive")
        if amount > self.balance:
            raise CommandRejected(f"insufficient funds: balance {self.balance}, requested {amount}")
        return [("MoneyWithdrawn", {"amount": amount, "channel": channel})]


# --------------------------------------------------------------------------
# The command handler: load -> decide -> append(expected_version) -> retry.
# --------------------------------------------------------------------------
def handle(store: EventStore, account_id: str, decide, *, retries: int = 3):
    """The universal event-sourced write path."""
    for attempt in range(retries + 1):
        acct, _ = Account.load(store, account_id)
        events = decide(acct)                                # may raise CommandRejected
        try:
            return store.append(f"account-{account_id}", acct.version, events)
        except ConcurrencyConflict:
            if attempt == retries:
                raise
            # loop: reload the fresher state and re-decide — the invariant is
            # re-checked against what ACTUALLY happened meanwhile.


SNAPSHOT_EVERY = 100

def maybe_snapshot(store: EventStore, account_id: str) -> None:
    stream = f"account-{account_id}"
    version = store.stream_version(stream)
    snap = store.load_snapshot(stream)
    if version - (snap[0] if snap else 0) >= SNAPSHOT_EVERY:
        acct, _ = Account.load(store, account_id)
        store.save_snapshot(stream, acct.version,
                            {"owner": acct.owner, "balance": acct.balance, "is_open": acct.is_open})


# --------------------------------------------------------------------------
# Narrated demo
# --------------------------------------------------------------------------
def main() -> None:
    store = EventStore()

    print("== 1. State is a replay, not a row ==")
    handle(store, "7", lambda a: a.decide_open("priya"))
    handle(store, "7", lambda a: a.decide_deposit(100.0))
    handle(store, "7", lambda a: a.decide_withdraw(30.0))
    acct, n = Account.load(store, "7")
    print(f"  events: {[(v, t) for v, t, _ in store.read('account-7')]}")
    print(f"  balance = {acct.balance} (computed by replaying {n} events; no balance column exists)")

    print("\n== 2. Time travel: the state at ANY past version ==")
    for v in range(1, acct.version + 1):
        past, _ = Account.load(store, "7", as_of_version=v)
        print(f"  as of version {v}: balance = {past.balance}")

    print("\n== 3. The invariant is checked against replayed truth ==")
    try:
        handle(store, "7", lambda a: a.decide_withdraw(1000.0))
    except CommandRejected as e:
        print(f"  withdraw 1000 rejected: {e}")

    print("\n== 4. Two tellers, one account: optimistic concurrency ==")
    a1, _ = Account.load(store, "7")             # teller 1 loads version 3 (balance 70)
    a2, _ = Account.load(store, "7")             # teller 2 loads the same version 3
    store.append("account-7", a1.version, a1.decide_withdraw(50.0))
    print(f"  teller 1: withdrew 50 at expected_version={a1.version} — OK (balance 70 -> 20)")
    try:
        store.append("account-7", a2.version, a2.decide_withdraw(40.0))
    except ConcurrencyConflict as e:
        print(f"  teller 2's blind append: REJECTED — {e}")
    try:
        handle(store, "7", lambda a: a.decide_withdraw(40.0))    # retry loop: reload + RE-DECIDE
    except CommandRejected as e:
        print(f"  teller 2's retry re-decided against FRESH state and the rule said no: {e}")
    acct, _ = Account.load(store, "7")
    print(f"  balance stands at {acct.balance}. Without expected_version, both withdrawals")
    print(f"  would have 'succeeded' against the stale balance of 70 — overdrawing to -20.")

    print("\n== 5. Mistakes are corrected, never erased ==")
    handle(store, "7", lambda a: [("MoneyDeposited", {"amount": 500.0, "channel": "wire"})])
    print("  a 500 deposit landed in the wrong account. UPDATE? DELETE? Neither exists.")
    handle(store, "7", lambda a: [("DepositReversed",
                                   {"amount": 500.0, "reason": "posted to wrong account", "reverses_version": a.version})])
    acct, _ = Account.load(store, "7")
    print(f"  a DepositReversed event compensates: balance back to {acct.balance};")
    print(f"  the audit trail keeps BOTH facts — the mistake and its correction.")

    print("\n== 6. Snapshots: replay cost, capped ==")
    for i in range(250):
        handle(store, "7", lambda a: a.decide_deposit(1.0))
        maybe_snapshot(store, "7")
    _, replayed_full = Account.load(store, "7")
    snap_acct, replayed_snap = Account.load(store, "7", use_snapshot=True)
    full_acct, _ = Account.load(store, "7")
    print(f"  stream has {store.stream_version('account-7')} events")
    print(f"  cold load replays {replayed_full}; snapshot load replays only {replayed_snap}")
    print(f"  identical state either way: {snap_acct.balance} == {full_acct.balance}")

    print("\n== 7. Old events, new code: upcasting ==")
    store.append("account-9", 0, [("AccountOpened", {"owner": "amit"})])
    store.append("account-9", 1, [("MoneyDeposited", {"amount": 10.0})], schema_v=1)  # 2019-era event
    v, t, payload = store.read("account-9")[-1]
    print(f"  a schema-v1 event (no 'channel' field) reads back as: {payload}")
    print("  history was never migrated; it is upgraded in memory, on read.")


if __name__ == "__main__":
    main()
