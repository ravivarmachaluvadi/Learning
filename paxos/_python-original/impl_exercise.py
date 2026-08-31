"""
Exercise rung 5 — IMPLEMENT the two decisions at the heart of Paxos.

Fill in the two functions below, then run:

    python3 -m unittest -v test_impl_exercise

Both are pure functions — no I/O, no classes. Between them they contain the
entire intellectual content of the protocol: what a proposer must propose,
and what "decided" actually means. Everything else in paxos.py is plumbing.

(Reference answers in SOLUTIONS.md — attempt first; the tests tell you
exactly which rule you got wrong.)
"""


def pick_value_from_promises(promises, my_value):
    """The phase-2 value rule.

    `promises` is the list of phase-1 replies a proposer collected from a
    majority. Each promise is a tuple:

        (acceptor_id, last_accepted_n, last_accepted_value)

    where last_accepted_value is None (and last_accepted_n is 0) if that
    acceptor has never accepted anything.

    Return the value this proposer MUST send in its Accept messages.
    """
    raise NotImplementedError("your code here")


def chosen_value(acceptor_states, quorum):
    """The decision rule.

    `acceptor_states` is a list with one tuple per acceptor:

        (accepted_n, accepted_value)        # (0, None) if nothing accepted

    Return the value that has been CHOSEN, or None if no value has been.
    Be careful about exactly what has to match for a decision to exist.
    """
    raise NotImplementedError("your code here")
