#!/usr/bin/env python3
"""Executable documentation: each test name is a scenario from README.md.

Run:  python3 -m unittest -v test_outbox
"""

import os
import tempfile
import unittest

from outbox_lab import Broker, Crash, InventoryService, OrderService, Relay


class OutboxScenarios(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.orders_db = os.path.join(self.dir.name, "orders.db")
        self.inv_db = os.path.join(self.dir.name, "inventory.db")
        self.broker = Broker()

    def tearDown(self):
        self.dir.cleanup()

    # ------------------------------------------------------------------
    # The problem
    # ------------------------------------------------------------------
    def test_dual_write_loses_message_when_crash_after_db_commit(self):
        svc = OrderService(self.orders_db)
        with self.assertRaises(Crash):
            svc.place_order_dual_write(
                self.broker, "red-mug", 2, crash_between=True
            )
        restarted = OrderService(self.orders_db)  # process restart
        self.assertEqual(len(restarted.orders()), 1, "order was committed")
        self.assertEqual(len(self.broker.log), 0, "event silently lost")

    def test_dual_write_works_only_when_nothing_crashes(self):
        svc = OrderService(self.orders_db)
        svc.place_order_dual_write(self.broker, "red-mug", 2)
        self.assertEqual(len(svc.orders()), 1)
        self.assertEqual(len(self.broker.log), 1)

    # ------------------------------------------------------------------
    # The pattern
    # ------------------------------------------------------------------
    def test_outbox_write_is_atomic_with_business_write(self):
        svc = OrderService(self.orders_db)
        svc.place_order_outbox("red-mug", 2)
        self.assertEqual(len(svc.orders()), 1)
        self.assertEqual(len(svc.unpublished_outbox_rows()), 1)

    def test_message_survives_crash_before_relay_runs(self):
        svc = OrderService(self.orders_db)
        svc.place_order_outbox("red-mug", 2)
        # producer process "crashes" here; relay starts later, fresh
        relay = Relay(self.orders_db)
        self.assertEqual(relay.poll_once(self.broker), 1)
        self.assertEqual(len(self.broker.log), 1)
        restarted = OrderService(self.orders_db)
        self.assertEqual(len(restarted.unpublished_outbox_rows()), 0)

    def test_relay_poll_is_noop_when_outbox_empty(self):
        OrderService(self.orders_db)
        relay = Relay(self.orders_db)
        self.assertEqual(relay.poll_once(self.broker), 0)

    def test_relay_preserves_commit_order(self):
        svc = OrderService(self.orders_db)
        svc.place_order_outbox("red-mug", 1)
        svc.place_order_outbox("blue-mug", 2)
        svc.place_order_outbox("green-mug", 3)
        Relay(self.orders_db).poll_once(self.broker)
        items = [m["payload"] for m in self.broker.log]
        self.assertIn("red-mug", items[0])
        self.assertIn("blue-mug", items[1])
        self.assertIn("green-mug", items[2])

    # ------------------------------------------------------------------
    # The price: at-least-once
    # ------------------------------------------------------------------
    def test_relay_crash_between_publish_and_mark_causes_duplicate(self):
        svc = OrderService(self.orders_db)
        svc.place_order_outbox("red-mug", 2)
        with self.assertRaises(Crash):
            Relay(self.orders_db).poll_once(
                self.broker, crash_after_publish=True
            )
        Relay(self.orders_db).poll_once(self.broker)  # relay restart
        self.assertEqual(len(self.broker.log), 2, "same message twice")
        self.assertEqual(
            self.broker.log[0]["message_id"], self.broker.log[1]["message_id"]
        )

    def test_naive_consumer_corrupts_data_on_duplicate(self):
        svc = OrderService(self.orders_db)
        svc.place_order_outbox("red-mug", 2)
        with self.assertRaises(Crash):
            Relay(self.orders_db).poll_once(
                self.broker, crash_after_publish=True
            )
        Relay(self.orders_db).poll_once(self.broker)
        inv = InventoryService(self.inv_db, idempotent=False)
        inv.set_stock("red-mug", 10)
        inv.consume_all(self.broker)
        self.assertEqual(inv.stock("red-mug"), 6, "decremented twice: wrong")

    def test_idempotent_consumer_survives_duplicate(self):
        svc = OrderService(self.orders_db)
        svc.place_order_outbox("red-mug", 2)
        with self.assertRaises(Crash):
            Relay(self.orders_db).poll_once(
                self.broker, crash_after_publish=True
            )
        Relay(self.orders_db).poll_once(self.broker)
        inv = InventoryService(self.inv_db, idempotent=True)
        inv.set_stock("red-mug", 10)
        applied, skipped = inv.consume_all(self.broker)
        self.assertEqual((applied, skipped), (1, 1))
        self.assertEqual(inv.stock("red-mug"), 8, "duplicate deduped: correct")

    def test_idempotent_consumer_resumes_after_its_own_restart(self):
        svc = OrderService(self.orders_db)
        svc.place_order_outbox("red-mug", 2)
        svc.place_order_outbox("red-mug", 3)
        Relay(self.orders_db).poll_once(self.broker)
        inv = InventoryService(self.inv_db, idempotent=True)
        inv.set_stock("red-mug", 10)
        inv.consume_all(self.broker)
        # consumer restarts over the same durable state, re-reads the log
        inv2 = InventoryService(self.inv_db, idempotent=True)
        applied, skipped = inv2.consume_all(self.broker)
        self.assertEqual(applied, 0, "offset was durable; nothing re-applied")
        self.assertEqual(inv2.stock("red-mug"), 5)


if __name__ == "__main__":
    unittest.main(verbosity=2)
