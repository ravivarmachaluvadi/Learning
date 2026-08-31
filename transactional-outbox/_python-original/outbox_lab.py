#!/usr/bin/env python3
"""Transactional outbox — single-process learning lab.

What is REAL here:      SQLite transactions (real ACID commits and rollbacks).
What is SIMULATED here: the message broker (an in-memory append-only log,
                        standing in for a Kafka topic) and process crashes
                        (a `Crash` exception raised at a chosen point; a
                        "restart" is just constructing fresh objects over the
                        same database file, exactly like a process restart
                        over a durable disk).

Cast of characters:
  OrderService   — the producer. Owns orders.db (tables: orders, outbox).
  Broker         — the messaging system. Durable, but a SEPARATE system:
                   it cannot join the database's transaction.
  Relay          — the message relay. Polls the outbox table, publishes to
                   the broker, marks rows as published.
  InventoryService — the consumer. Owns inventory.db (tables: inventory,
                   processed_messages). Reads the broker log and decrements
                   stock for each order-placed event.

Run the guided experiments:
  python3 outbox_lab.py dual-write-crash
  python3 outbox_lab.py outbox-survives-crash
  python3 outbox_lab.py relay-crash-duplicate
  python3 outbox_lab.py idempotent-consumer
  python3 outbox_lab.py all
"""

import json
import os
import sqlite3
import sys
import uuid


class Crash(Exception):
    """Simulates the process dying at an exact point in the code."""


# ---------------------------------------------------------------------------
# The broker: durable, ordered, append-only — but OUTSIDE the database.
# ---------------------------------------------------------------------------
class Broker:
    """Stands in for one Kafka topic / RabbitMQ queue.

    It survives producer crashes (it is a separate system), and consumers
    read it as an ordered log. What it can NOT do is take part in the
    producer's database transaction — that impossibility is the entire
    reason the outbox pattern exists.
    """

    def __init__(self):
        self.log = []  # list of dicts: {"message_id", "topic", "payload"}

    def publish(self, message_id, topic, payload):
        self.log.append(
            {"message_id": message_id, "topic": topic, "payload": payload}
        )


# ---------------------------------------------------------------------------
# Producer side: the order service and its database
# ---------------------------------------------------------------------------
class OrderService:
    def __init__(self, db_path):
        self.db_path = db_path
        self.conn = sqlite3.connect(db_path)
        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS orders ("
            " id TEXT PRIMARY KEY, item TEXT, qty INTEGER)"
        )
        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS outbox ("
            " seq INTEGER PRIMARY KEY AUTOINCREMENT,"  # preserves send order
            " message_id TEXT UNIQUE,"
            " topic TEXT, payload TEXT,"
            " published_at TEXT)"                       # NULL = not yet sent
        )
        self.conn.commit()

    # --- the WRONG way: dual write ------------------------------------
    def place_order_dual_write(self, broker, item, qty, crash_between=False):
        """Write to the DB, then publish to the broker: two separate systems,
        two separate writes, no shared transaction."""
        order_id = str(uuid.uuid4())[:8]
        self.conn.execute(
            "INSERT INTO orders (id, item, qty) VALUES (?, ?, ?)",
            (order_id, item, qty),
        )
        self.conn.commit()                      # write #1: durable in the DB

        if crash_between:
            raise Crash("process died after DB commit, before publish")

        broker.publish(                          # write #2: a different system
            str(uuid.uuid4())[:8],
            "order-placed",
            json.dumps({"order_id": order_id, "item": item, "qty": qty}),
        )
        return order_id

    # --- the RIGHT way: outbox ----------------------------------------
    def place_order_outbox(self, item, qty):
        """ONE local transaction writes both the order and the outgoing
        message. Either both become durable or neither does."""
        order_id = str(uuid.uuid4())[:8]
        message_id = str(uuid.uuid4())[:8]
        with self.conn:  # one atomic SQLite transaction
            self.conn.execute(
                "INSERT INTO orders (id, item, qty) VALUES (?, ?, ?)",
                (order_id, item, qty),
            )
            self.conn.execute(
                "INSERT INTO outbox (message_id, topic, payload)"
                " VALUES (?, ?, ?)",
                (
                    message_id,
                    "order-placed",
                    json.dumps({"order_id": order_id, "item": item, "qty": qty}),
                ),
            )
        return order_id

    def orders(self):
        return self.conn.execute("SELECT id, item, qty FROM orders").fetchall()

    def unpublished_outbox_rows(self):
        return self.conn.execute(
            "SELECT seq, message_id, topic, payload FROM outbox"
            " WHERE published_at IS NULL ORDER BY seq"
        ).fetchall()


# ---------------------------------------------------------------------------
# The relay: outbox table -> broker
# ---------------------------------------------------------------------------
class Relay:
    """Polls the producer's outbox table and publishes pending rows.

    Note the unavoidable gap: `publish` and `mark as published` are again
    two systems, so the relay can crash between them. That is why delivery
    is AT-LEAST-once, never exactly-once.
    """

    def __init__(self, db_path):
        self.conn = sqlite3.connect(db_path)

    def poll_once(self, broker, crash_after_publish=False):
        rows = self.conn.execute(
            "SELECT seq, message_id, topic, payload FROM outbox"
            " WHERE published_at IS NULL ORDER BY seq"
        ).fetchall()
        sent = 0
        for seq, message_id, topic, payload in rows:
            broker.publish(message_id, topic, payload)
            if crash_after_publish:
                raise Crash(
                    "relay died after publishing message %s,"
                    " before marking it published" % message_id
                )
            with self.conn:
                self.conn.execute(
                    "UPDATE outbox SET published_at = datetime('now')"
                    " WHERE seq = ?",
                    (seq,),
                )
            sent += 1
        return sent


# ---------------------------------------------------------------------------
# Consumer side: the inventory service and its own database
# ---------------------------------------------------------------------------
class InventoryService:
    def __init__(self, db_path, idempotent):
        self.idempotent = idempotent
        self.conn = sqlite3.connect(db_path)
        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS inventory ("
            " item TEXT PRIMARY KEY, stock INTEGER)"
        )
        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS processed_messages ("
            " message_id TEXT PRIMARY KEY)"        # the 'inbox' dedupe table
        )
        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS consumer_offset ("
            " id INTEGER PRIMARY KEY CHECK (id = 1), next_index INTEGER)"
        )
        self.conn.execute(
            "INSERT OR IGNORE INTO consumer_offset (id, next_index)"
            " VALUES (1, 0)"
        )
        self.conn.commit()

    def stock(self, item):
        row = self.conn.execute(
            "SELECT stock FROM inventory WHERE item = ?", (item,)
        ).fetchone()
        return row[0] if row else None

    def set_stock(self, item, qty):
        with self.conn:
            self.conn.execute(
                "INSERT OR REPLACE INTO inventory (item, stock) VALUES (?, ?)",
                (item, qty),
            )

    def consume_all(self, broker, narrate=False):
        """Read every not-yet-consumed message from the broker log and apply
        it. With idempotent=True, a message_id seen before is skipped —
        the skip-check and the stock update share one transaction."""
        applied, skipped = 0, 0
        while True:
            (next_index,) = self.conn.execute(
                "SELECT next_index FROM consumer_offset WHERE id = 1"
            ).fetchone()
            if next_index >= len(broker.log):
                return applied, skipped
            msg = broker.log[next_index]
            body = json.loads(msg["payload"])
            with self.conn:  # apply + dedupe-record + offset: one transaction
                duplicate = False
                if self.idempotent:
                    seen = self.conn.execute(
                        "SELECT 1 FROM processed_messages WHERE message_id = ?",
                        (msg["message_id"],),
                    ).fetchone()
                    if seen:
                        duplicate = True
                    else:
                        self.conn.execute(
                            "INSERT INTO processed_messages (message_id)"
                            " VALUES (?)",
                            (msg["message_id"],),
                        )
                if not duplicate:
                    self.conn.execute(
                        "UPDATE inventory SET stock = stock - ?"
                        " WHERE item = ?",
                        (body["qty"], body["item"]),
                    )
                self.conn.execute(
                    "UPDATE consumer_offset SET next_index = ? WHERE id = 1",
                    (next_index + 1,),
                )
            if duplicate:
                skipped += 1
                if narrate:
                    print(
                        "    consumer: message %s already processed — SKIPPED"
                        % msg["message_id"]
                    )
            else:
                applied += 1
                if narrate:
                    print(
                        "    consumer: applied message %s (%s x%d) -> stock now %d"
                        % (
                            msg["message_id"],
                            body["item"],
                            body["qty"],
                            self.stock(body["item"]),
                        )
                    )


# ---------------------------------------------------------------------------
# Guided experiments
# ---------------------------------------------------------------------------
def _fresh_paths(name):
    base = os.path.join(
        os.path.dirname(os.path.abspath(__file__)), ".lab-%s" % name
    )
    for suffix in ("orders.db", "inventory.db"):
        path = "%s-%s" % (base, suffix)
        if os.path.exists(path):
            os.remove(path)
    return "%s-orders.db" % base, "%s-inventory.db" % base


def experiment_dual_write_crash():
    print("=" * 72)
    print("EXPERIMENT 1: dual write, crash between DB commit and publish")
    print("=" * 72)
    orders_db, _ = _fresh_paths("exp1")
    broker = Broker()
    svc = OrderService(orders_db)
    print("Placing order for 2 x red-mug with a crash injected between the")
    print("database commit and the broker publish...")
    try:
        svc.place_order_dual_write(broker, "red-mug", 2, crash_between=True)
    except Crash as e:
        print("  CRASH: %s" % e)
    print("Process 'restarts' (new objects, same database file):")
    svc2 = OrderService(orders_db)
    print("  orders table:        %s" % svc2.orders())
    print("  broker log:          %s messages" % len(broker.log))
    print()
    print("VERDICT: the order is durably saved but the event never existed.")
    print("Nothing in the system knows anything is missing — there is no")
    print("record of the intent to send. This is the silent-loss failure.")
    print()


def experiment_outbox_survives_crash():
    print("=" * 72)
    print("EXPERIMENT 2: outbox, crash at the same point — nothing is lost")
    print("=" * 72)
    orders_db, _ = _fresh_paths("exp2")
    broker = Broker()
    svc = OrderService(orders_db)
    svc.place_order_outbox("red-mug", 2)
    print("Order + outbox row committed in ONE transaction. The process now")
    print("crashes before any relay ever ran (same crash point as before).")
    print("Process restarts:")
    svc2 = OrderService(orders_db)
    print("  orders table:        %s" % svc2.orders())
    print("  unpublished outbox:  %s" % svc2.unpublished_outbox_rows())
    relay = Relay(orders_db)
    sent = relay.poll_once(broker)
    print("Relay polls once: published %d message(s)." % sent)
    print("  broker log:          %s messages" % len(broker.log))
    print()
    print("VERDICT: the intent to send was committed WITH the order, so a")
    print("crash only delays the message; it can never erase it.")
    print()


def experiment_relay_crash_duplicate(idempotent=False, banner=True):
    if banner:
        print("=" * 72)
        print("EXPERIMENT 3: relay crashes after publish, before marking")
        print("=" * 72)
    orders_db, inv_db = _fresh_paths("exp3" + ("i" if idempotent else ""))
    broker = Broker()
    svc = OrderService(orders_db)
    inv = InventoryService(inv_db, idempotent=idempotent)
    inv.set_stock("red-mug", 10)
    svc.place_order_outbox("red-mug", 2)
    print("Stock starts at 10. One order for 2 x red-mug is in the outbox.")
    relay = Relay(orders_db)
    try:
        relay.poll_once(broker, crash_after_publish=True)
    except Crash as e:
        print("  CRASH: %s" % e)
    print("Relay restarts and polls again. The row is still unmarked, so:")
    relay2 = Relay(orders_db)
    relay2.poll_once(broker)
    print("  broker log now holds %d copies of the same message_id."
          % len(broker.log))
    label = "IDEMPOTENT" if idempotent else "NAIVE"
    print("Consumer (%s) processes the log:" % label)
    inv.consume_all(broker, narrate=True)
    print("  final stock: %d (correct answer: 8)" % inv.stock("red-mug"))
    print()
    if idempotent:
        print("VERDICT: the duplicate reached the consumer but was recognized")
        print("by its message_id and skipped. At-least-once delivery +")
        print("idempotent processing = effectively-once OUTCOME.")
    else:
        print("VERDICT: at-least-once delivery is the price of never losing a")
        print("message. A naive consumer turns the duplicate into wrong data.")
    print()


def experiment_idempotent_consumer():
    print("=" * 72)
    print("EXPERIMENT 4: same relay crash, idempotent consumer")
    print("=" * 72)
    experiment_relay_crash_duplicate(idempotent=True, banner=False)


EXPERIMENTS = {
    "dual-write-crash": experiment_dual_write_crash,
    "outbox-survives-crash": experiment_outbox_survives_crash,
    "relay-crash-duplicate": experiment_relay_crash_duplicate,
    "idempotent-consumer": experiment_idempotent_consumer,
}

if __name__ == "__main__":
    choice = sys.argv[1] if len(sys.argv) > 1 else "all"
    if choice == "all":
        for fn in EXPERIMENTS.values():
            fn()
    elif choice in EXPERIMENTS:
        EXPERIMENTS[choice]()
    else:
        print("usage: python3 outbox_lab.py [%s|all]"
              % "|".join(EXPERIMENTS))
        sys.exit(1)
