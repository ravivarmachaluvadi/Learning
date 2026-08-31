"""
CQRS learning lab — a single-process, two-database simulation.

The shop: customers place and cancel orders (COMMANDS, few per second, must
be correct) while dashboards and reports are read constantly (QUERIES, many
per second, must be fast). We keep TWO stores with DIFFERENT SHAPES:

  write.db (normalized, guarded by invariants)     read.db (denormalized, query-shaped)
  ---------------------------------------------    ------------------------------------
  orders(id, customer, status, version)            customer_dashboard(customer, order_id,
  order_items(order_id, product, qty, price)           status, total)      <- one row per order, pre-joined
  events(seq, type, payload)  <- the feed          daily_revenue(day, revenue)
                                                   projector_checkpoint(last_seq)

A PROJECTOR moves events from write.db to read.db — asynchronously, in
batches, whenever it gets to run. The gap between the two stores is the
"consistency window" this whole lab is about.

Run the narrated demo:   python3 cqrs_lab.py
Run the tests:           python3 -m unittest -v test_cqrs
No dependencies beyond the standard library.
"""
from __future__ import annotations
import json
import sqlite3
from dataclasses import dataclass


# --------------------------------------------------------------------------
# The write side: commands, invariants, and the event feed
# --------------------------------------------------------------------------
class CommandRejected(Exception):
    """The write model said no. Nothing was stored, no event was produced."""


class WriteSide:
    """Normalized store + business rules. The ONLY place data changes."""

    def __init__(self) -> None:
        self.db = sqlite3.connect(":memory:")
        self.db.execute("CREATE TABLE orders (id TEXT PRIMARY KEY, customer TEXT, status TEXT, version INTEGER)")
        self.db.execute("CREATE TABLE order_items (order_id TEXT, product TEXT, qty INTEGER, price REAL)")
        # The event feed. In production this is an outbox table drained to a
        # broker (see the transactional-outbox lab) — here we read it directly.
        self.db.execute("CREATE TABLE events (seq INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, payload TEXT)")

    # -- commands ----------------------------------------------------------
    def place_order(self, order_id: str, customer: str, items: list[tuple[str, int, float]], day: str) -> None:
        """Command: validate invariants, mutate the write model, and append the
        event — all in ONE local transaction (this atomicity is the outbox idea)."""
        if not items:
            raise CommandRejected("an order needs at least one item")
        if any(qty <= 0 or price < 0 for _, qty, price in items):
            raise CommandRejected("quantities must be positive and prices non-negative")
        with self.db:  # one transaction: state change + event, atomically
            if self.db.execute("SELECT 1 FROM orders WHERE id=?", (order_id,)).fetchone():
                raise CommandRejected(f"order {order_id} already exists")
            self.db.execute("INSERT INTO orders VALUES (?,?,?,1)", (order_id, customer, "PLACED"))
            self.db.executemany("INSERT INTO order_items VALUES (?,?,?,?)",
                                [(order_id, p, q, pr) for p, q, pr in items])
            total = sum(q * pr for _, q, pr in items)
            self._append("OrderPlaced", {"order_id": order_id, "customer": customer,
                                         "total": total, "day": day,
                                         "items": [{"product": p, "qty": q} for p, q, _ in items]})

    def cancel_order(self, order_id: str, day: str) -> None:
        with self.db:
            row = self.db.execute("SELECT status, customer, version FROM orders WHERE id=?", (order_id,)).fetchone()
            if row is None:
                raise CommandRejected(f"no such order {order_id}")
            status, customer, version = row
            if status == "SHIPPED":
                raise CommandRejected("cannot cancel a shipped order")   # the invariant lives HERE, once
            if status == "CANCELLED":
                raise CommandRejected("already cancelled")
            total = self.db.execute("SELECT SUM(qty*price) FROM order_items WHERE order_id=?",
                                    (order_id,)).fetchone()[0]
            self.db.execute("UPDATE orders SET status='CANCELLED', version=? WHERE id=?", (version + 1, order_id))
            self._append("OrderCancelled", {"order_id": order_id, "customer": customer,
                                            "total": total, "day": day})

    def mark_shipped(self, order_id: str) -> None:
        with self.db:
            row = self.db.execute("SELECT status, version, customer FROM orders WHERE id=?", (order_id,)).fetchone()
            if row is None or row[0] != "PLACED":
                raise CommandRejected("only a placed order can ship")
            self.db.execute("UPDATE orders SET status='SHIPPED', version=? WHERE id=?", (row[1] + 1, order_id))
            self._append("OrderShipped", {"order_id": order_id, "customer": row[2]})

    def _append(self, type_: str, payload: dict) -> None:
        self.db.execute("INSERT INTO events (type, payload) VALUES (?,?)", (type_, json.dumps(payload)))

    # -- the feed the projector consumes -----------------------------------
    def events_after(self, seq: int, limit: int = 100) -> list[tuple[int, str, dict]]:
        rows = self.db.execute("SELECT seq, type, payload FROM events WHERE seq>? ORDER BY seq LIMIT ?",
                               (seq, limit)).fetchall()
        return [(s, t, json.loads(p)) for s, t, p in rows]

    def last_seq(self) -> int:
        return self.db.execute("SELECT COALESCE(MAX(seq),0) FROM events").fetchone()[0]


# --------------------------------------------------------------------------
# The read side: query-shaped tables + the projector that fills them
# --------------------------------------------------------------------------
class ReadSide:
    """Denormalized store. Rebuildable from the event feed at any time."""

    def __init__(self) -> None:
        self.db = sqlite3.connect(":memory:")
        self.db.execute("CREATE TABLE customer_dashboard (customer TEXT, order_id TEXT PRIMARY KEY, status TEXT, total REAL)")
        self.db.execute("CREATE TABLE daily_revenue (day TEXT PRIMARY KEY, revenue REAL)")
        self.db.execute("CREATE TABLE projector_checkpoint (id INTEGER PRIMARY KEY CHECK (id=1), last_seq INTEGER)")
        self.db.execute("INSERT INTO projector_checkpoint VALUES (1, 0)")
        self.db.commit()

    # -- queries: pure SELECTs, no joins, no business logic ----------------
    def dashboard(self, customer: str) -> list[tuple[str, str, float]]:
        return self.db.execute("SELECT order_id, status, total FROM customer_dashboard "
                               "WHERE customer=? ORDER BY order_id", (customer,)).fetchall()

    def revenue(self, day: str) -> float:
        row = self.db.execute("SELECT revenue FROM daily_revenue WHERE day=?", (day,)).fetchone()
        return row[0] if row else 0.0

    def checkpoint(self) -> int:
        return self.db.execute("SELECT last_seq FROM projector_checkpoint").fetchone()[0]


class Projector:
    """Consumes the event feed, updates the read side. Runs when told to —
    the lab controls WHEN, which is exactly what makes staleness visible.

    Correctness relies on two properties, both breakable via flags so the
    tests (and you) can watch what protects what:
      * idempotent=True    — applying the same event twice changes nothing
                             (upserts / guarded updates, not blind += )
      * checkpoint_in_txn  — projection writes and the checkpoint advance
                             commit atomically, so a crash between them
                             cannot double-apply or lose events.
    """

    def __init__(self, write: WriteSide, read: ReadSide, *,
                 idempotent: bool = True, checkpoint_in_txn: bool = True) -> None:
        self.write, self.read = write, read
        self.idempotent, self.checkpoint_in_txn = idempotent, checkpoint_in_txn

    def lag(self) -> int:
        return self.write.last_seq() - self.read.checkpoint()

    def run_once(self, batch: int = 100, crash_before_checkpoint: bool = False) -> int:
        """Process one batch. Returns how many events were applied."""
        events = self.write.events_after(self.read.checkpoint(), batch)
        if not events:
            return 0
        db = self.read.db
        try:
            with db:
                for seq, type_, payload in events:
                    self._apply(type_, payload)
                    if not self.checkpoint_in_txn:
                        db.commit()                      # projection visible NOW...
                    if crash_before_checkpoint:
                        raise RuntimeError("projector crashed mid-batch")
                db.execute("UPDATE projector_checkpoint SET last_seq=?", (events[-1][0],))
        except RuntimeError:
            # crash simulated: transaction rolled back (or, without
            # checkpoint_in_txn, partially committed — the bug)
            raise
        return len(events)

    def _apply(self, type_: str, e: dict) -> None:
        db = self.read.db
        if type_ == "OrderPlaced":
            if self.idempotent:
                # upsert: replaying the same event leaves the same row
                cur = db.execute("INSERT OR IGNORE INTO customer_dashboard VALUES (?,?,?,?)",
                                 (e["customer"], e["order_id"], "PLACED", e["total"]))
                if cur.rowcount:                          # only count revenue on FIRST apply
                    db.execute("INSERT INTO daily_revenue VALUES (?,?) "
                               "ON CONFLICT(day) DO UPDATE SET revenue = revenue + excluded.revenue",
                               (e["day"], e["total"]))
            else:
                # the naive version: blind insert-or-replace + blind add
                db.execute("INSERT OR REPLACE INTO customer_dashboard VALUES (?,?,?,?)",
                           (e["customer"], e["order_id"], "PLACED", e["total"]))
                db.execute("INSERT INTO daily_revenue VALUES (?,?) "
                           "ON CONFLICT(day) DO UPDATE SET revenue = revenue + excluded.revenue",
                           (e["day"], e["total"]))
        elif type_ == "OrderCancelled":
            if self.idempotent:
                cur = db.execute("UPDATE customer_dashboard SET status='CANCELLED' "
                                 "WHERE order_id=? AND status!='CANCELLED'", (e["order_id"],))
                if cur.rowcount:
                    db.execute("UPDATE daily_revenue SET revenue = revenue - ? WHERE day=?",
                               (e["total"], e["day"]))
            else:
                db.execute("UPDATE customer_dashboard SET status='CANCELLED' WHERE order_id=?", (e["order_id"],))
                db.execute("UPDATE daily_revenue SET revenue = revenue - ? WHERE day=?", (e["total"], e["day"]))
        elif type_ == "OrderShipped":
            db.execute("UPDATE customer_dashboard SET status='SHIPPED' WHERE order_id=?", (e["order_id"],))


def wait_for(projector: Projector, seq: int) -> None:
    """Read-your-own-writes helper: make sure the read side has caught up to
    at least event `seq` before querying (in production: poll, or route this
    one query to the write side, or use a session token)."""
    while projector.read.checkpoint() < seq:
        projector.run_once()


# --------------------------------------------------------------------------
# Narrated demo
# --------------------------------------------------------------------------
def main() -> None:
    write, read = WriteSide(), ReadSide()
    projector = Projector(write, read)

    print("== 1. A command succeeds; the query side hasn't heard yet ==")
    write.place_order("ord-1", "priya", [("keyboard", 1, 80.0), ("mouse", 2, 20.0)], day="2026-08-27")
    print(f"  command OK; write side has the order; event seq={write.last_seq()} appended")
    print(f"  dashboard('priya') RIGHT NOW: {read.dashboard('priya')}   <- stale: projector hasn't run")
    print(f"  lag = {projector.lag()} event(s)")

    print("\n== 2. The projector catches up; the query is fresh ==")
    applied = projector.run_once()
    print(f"  projector applied {applied} event(s); checkpoint={read.checkpoint()}")
    print(f"  dashboard('priya'): {read.dashboard('priya')}")
    print(f"  revenue('2026-08-27'): {read.revenue('2026-08-27')}")

    print("\n== 3. Invariants live on the write side only ==")
    write.mark_shipped("ord-1")
    try:
        write.cancel_order("ord-1", day="2026-08-27")
    except CommandRejected as e:
        print(f"  cancel rejected: {e}   <- the read side never had to know the rule")

    print("\n== 4. Rebuild the whole read side from the event feed ==")
    fresh = ReadSide()
    Projector(write, fresh).run_once()
    print(f"  fresh read.db after replay: dashboard={fresh.dashboard('priya')}, "
          f"revenue={fresh.revenue('2026-08-27')}")
    print("  identical — the read side is a cache, the events are the truth of record here.")

    print("\n== 5. Duplicate delivery: idempotent vs naive projector ==")
    naive_read = ReadSide()
    naive = Projector(write, naive_read, idempotent=False, checkpoint_in_txn=True)
    naive.run_once()
    # simulate at-least-once delivery: rewind the checkpoint and redeliver
    naive_read.db.execute("UPDATE projector_checkpoint SET last_seq=0"); naive_read.db.commit()
    naive.run_once()
    print(f"  naive projector after a redelivery: revenue = {naive_read.revenue('2026-08-27')}   <- DOUBLE-COUNTED")
    good_read = ReadSide()
    good = Projector(write, good_read)
    good.run_once()
    good_read.db.execute("UPDATE projector_checkpoint SET last_seq=0"); good_read.db.commit()
    good.run_once()
    print(f"  idempotent projector after the same redelivery: revenue = {good_read.revenue('2026-08-27')}")


if __name__ == "__main__":
    main()
