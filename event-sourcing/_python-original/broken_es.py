"""
DEBUG EXERCISE D1 — "our retry logic is battle-tested: it never fails."

Someone found ConcurrencyConflict annoying, so they wrote a handler that
"just retries harder": on conflict it fetches the CURRENT stream version and
appends the same events again. No failed requests! The bank is delighted —
until the auditors find accounts below zero.

Reproduce it, explain the exact sequence, name the test in test_es.py that
pins the correct behavior, and fix the handler. (Answers in SOLUTIONS.md.)

Run:  python3 broken_es.py
"""
from es_lab import Account, ConcurrencyConflict, EventStore, handle


def handle_with_more_determination(store: EventStore, account_id: str, decide):
    """Load once, decide once — and on conflict, slam the same events in at
    whatever version the stream is at now. What could go wrong?"""
    stream = f"account-{account_id}"
    acct, _ = Account.load(store, account_id)
    events = decide(acct)                     # decided against the ORIGINAL state...
    while True:
        try:
            return store.append(stream, acct.version, events)
        except ConcurrencyConflict:
            acct = Account(account_id, version=store.stream_version(stream))  # ...never re-decided


if __name__ == "__main__":
    store = EventStore()
    handle(store, "7", lambda a: a.decide_open("priya"))
    handle(store, "7", lambda a: a.decide_deposit(70.0))

    # two tellers, both see balance 70, both withdraw 50 "successfully":
    t1, _ = Account.load(store, "7")
    t2_events_decided_at_v2 = Account.load(store, "7")[0].decide_withdraw(50.0)
    store.append("account-7", t1.version, t1.decide_withdraw(50.0))
    handle_with_more_determination(store, "7", lambda a: t2_events_decided_at_v2)

    acct, _ = Account.load(store, "7")
    print(f"two withdrawals of 50 from a balance of 70 both 'succeeded'.")
    print(f"balance = {acct.balance}   <- the overdraft rule exists... and was never consulted the second time")
