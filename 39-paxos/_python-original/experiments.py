"""
experiments.py — break Paxos on purpose and watch what it does.

    python3 experiments.py         # run all five
    python3 experiments.py 4       # run just one

Each experiment prints every message as it is delivered, so you can follow the
protocol one step at a time. The final line of each is the verdict.
"""

import sys

from paxos import Cluster


def banner(num: int, title: str, question: str) -> None:
    print()
    print("=" * 74)
    print(f" EXPERIMENT {num} — {title}")
    print(f" {question}")
    print("=" * 74)


def verdict(c: Cluster, expected: str) -> None:
    print()
    print(c.table())
    print()
    chosen = c.chosen_values()
    print(f" chosen value(s): {chosen if chosen else '(nothing chosen)'}")
    print(f" safety holds (at most one value ever chosen): {c.safety_holds()}")
    print(f" >> {expected}")


def full_round(c: Cluster, pid: str, n: int, acceptors: str) -> None:
    """One complete, successful round: prepare -> promise -> accept -> accepted."""
    c.propose(pid, n=n)
    c.deliver_many(acceptors, "PREPARE", n)
    for _ in acceptors:
        c.deliver_to(pid, "PROMISE", n)
    c.deliver_many(acceptors, "ACCEPT", n)
    for _ in acceptors:
        c.deliver_to(pid, "ACCEPTED", n)


def new_cluster(**kw) -> Cluster:
    c = Cluster(num_acceptors=5, trace=True, **kw)
    c.add_proposer("P1", "Zoo")
    c.add_proposer("P2", "Museum")
    print(f" 5 acceptors A-E, majority = {c.quorum}.  P1 wants 'Zoo', P2 wants 'Museum'.")
    return c


# ---------------------------------------------------------------------------

def experiment_1() -> None:
    banner(1, "the happy path", "One proposer, nothing broken. What does a clean run look like?")
    c = new_cluster()
    print("\n-- P1 runs both phases with A, B, C --")
    full_round(c, "P1", 1, "ABC")
    verdict(c, "Nobody had accepted anything, so P1 was free to use its own value.")


def experiment_2() -> None:
    banner(2, "a chosen value can never be changed",
           "P1 gets 'Zoo' chosen. Can P2 then push 'Museum' through?")
    c = new_cluster()
    print("\n-- P1 gets 'Zoo' chosen by the majority {A,B,C} --")
    full_round(c, "P1", 1, "ABC")
    print(f"\n  'Zoo' is now CHOSEN by {sorted(c.chosen_quorum)}.")

    print("\n-- P2 tries with a DIFFERENT majority {C,D,E} and a higher number --")
    c.propose("P2", n=2)
    c.deliver_many("CDE", "PREPARE", 2)
    for _ in "CDE":
        c.deliver_to("P2", "PROMISE", 2)

    p2 = c.proposers["P2"]
    print(f"\n  C is in BOTH majorities: {sorted(c.chosen_quorum)} and {{C,D,E}}.")
    print(f"  C reported it had already accepted {p2.proposed_value!r} at n={p2.adopted_from}.")
    print(f"  P2 wanted {p2.own_value!r} but is forced to propose {p2.proposed_value!r}.")
    c.deliver_many("CDE", "ACCEPT", 2)
    for _ in "CDE":
        c.deliver_to("P2", "ACCEPTED", 2)
    verdict(c, "Quorum overlap did the work: 'Museum' was never even proposed.")


def experiment_3() -> None:
    banner(3, "accepted by SOME is not the same as chosen",
           "P1's ACCEPT reaches only 2 of 5. Is 'Zoo' safe from replacement?")
    c = new_cluster()
    print("\n-- P1 prepares with A,B,C, then its ACCEPT reaches only A and B --")
    c.propose("P1", n=1)
    c.deliver_many("ABC", "PREPARE", 1)
    for _ in "ABC":
        c.deliver_to("P1", "PROMISE", 1)
    c.deliver_many("AB", "ACCEPT", 1)
    for d in "CDE":
        c.drop_to(d, "ACCEPT", 1)
    print(f"\n  2 of 5 accepted 'Zoo'. Majority is {c.quorum}, so NOTHING is chosen yet.")

    print("\n-- P2 now runs a full round with C, D, E --")
    c.propose("P2", n=2)
    c.deliver_many("CDE", "PREPARE", 2)
    for _ in "CDE":
        c.deliver_to("P2", "PROMISE", 2)
    print(f"\n  None of C,D,E had accepted anything, so P2 keeps its own value "
          f"{c.proposers['P2'].proposed_value!r}.")
    c.deliver_many("CDE", "ACCEPT", 2)
    for _ in "CDE":
        c.deliver_to("P2", "ACCEPTED", 2)
    verdict(c, "'Museum' wins, and that is CORRECT: 'Zoo' had never been chosen. "
               "A had accepted it, but no majority had.")


def experiment_4() -> None:
    banner(4, "duelling proposers (livelock)",
           "Two proposers keep gazumping each other. Does Paxos still terminate?")
    c = new_cluster()
    c.trace = False
    print("\n-- eight rounds, P1 and P2 alternating, no crashes, no lost messages --")
    n = 1
    for rnd in range(1, 9):
        c.propose("P1", n=n)
        c.deliver_many("ABC", "PREPARE", n)
        for _ in "ABC":
            c.deliver_to("P1", "PROMISE", n)

        c.propose("P2", n=n + 1)
        c.deliver_many("ABC", "PREPARE", n + 1)
        for _ in "ABC":
            c.deliver_to("P2", "PROMISE", n + 1)

        c.deliver_many("ABC", "ACCEPT", n)
        print(f"  round {rnd}: P1 prepared n={n}, P2 gazumped with n={n+1}, "
              f"P1's ACCEPT(n={n}) refused -> chosen so far: "
              f"{c.chosen_values() or '(nothing)'}")
        n += 2

    print("\n-- now P2 stands down (this is what electing a leader buys you) --")
    c.trace = True
    full_round(c, "P1", 101, "ABC")
    verdict(c, "Safety was never in danger; only progress was. A single "
               "proposer finishes in one round.")


def experiment_5() -> None:
    banner(5, "the amnesiac acceptor",
           "An acceptor keeps its promises in RAM instead of on disk. What breaks?")
    c = new_cluster(durable=False)
    print("\n-- P1 gets 'Zoo' chosen by A, B, C --")
    full_round(c, "P1", 1, "ABC")

    print("\n-- A, B and C reboot. Being non-durable, they forget everything --")
    c.crash("A", "B", "C")
    c.recover("A", "B", "C")
    print()
    print(c.table())

    print("\n-- P2 now runs a normal, entirely rule-abiding round with A, B, C --")
    full_round(c, "P2", 2, "ABC")
    verdict(c, "TWO different values were chosen. Consensus is broken -- and no "
               "node did anything 'wrong'. This is why acceptors must fsync "
               "promised_n / accepted_n / accepted_value BEFORE replying.")


EXPERIMENTS = [experiment_1, experiment_2, experiment_3, experiment_4, experiment_5]

if __name__ == "__main__":
    if len(sys.argv) > 1:
        EXPERIMENTS[int(sys.argv[1]) - 1]()
    else:
        for fn in EXPERIMENTS:
            fn()
        print("\n" + "=" * 74)
        print(" Experiments 1-4: safety held in every one.")
        print(" Experiment 5: safety broke -- because an acceptor lied about its past.")
        print("=" * 74)
