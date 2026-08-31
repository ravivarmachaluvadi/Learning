"""
paxos.py — single-decree ("basic") Paxos you can step through, break, and watch.

Everything runs in ONE process. The "network" is a message queue you control:
you decide which message is delivered next, which is dropped, and which node
is crashed. That is the whole point — in a real distributed system you do not
control the schedule, so the protocol must be correct under EVERY schedule.
Here you get to play the adversary.

Read README.md alongside this file. Run experiments.py to see it move.
"""

from __future__ import annotations

import random
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Set, Tuple


def quorum_size(num_acceptors: int) -> int:
    """A majority of the acceptors.

    Any two majorities of the same group always share at least one member.
    That overlap is the entire reason Paxos is safe: a later attempt cannot
    avoid meeting somebody who remembers the earlier decision.
    """
    return num_acceptors // 2 + 1


# --------------------------------------------------------------------------
# Messages
# --------------------------------------------------------------------------

@dataclass
class Message:
    """One network message. Five kinds, and that is the entire protocol."""
    src: str
    dst: str
    kind: str                    # PREPARE | PROMISE | ACCEPT | ACCEPTED | NACK
    n: int = 0                   # proposal number this message is about
    value: Any = None            # ACCEPT / ACCEPTED: the value being proposed
    last_n: int = 0              # PROMISE: acceptor's highest ACCEPTED number
    last_value: Any = None       # PROMISE: the value that went with last_n
    #                              NACK: the promise number that beat us

    def __str__(self) -> str:
        if self.kind == "PREPARE":
            return f"{self.src}->{self.dst}  PREPARE(n={self.n})"
        if self.kind == "PROMISE":
            if self.last_value is None:
                tail = "nothing accepted yet"
            else:
                tail = f"already accepted (n={self.last_n}, {self.last_value!r})"
            return f"{self.src}->{self.dst}  PROMISE(n={self.n}) [{tail}]"
        if self.kind == "ACCEPT":
            return f"{self.src}->{self.dst}  ACCEPT(n={self.n}, {self.value!r})"
        if self.kind == "ACCEPTED":
            return f"{self.src}->{self.dst}  ACCEPTED(n={self.n}, {self.value!r})"
        if self.kind == "NACK":
            return f"{self.src}->{self.dst}  NACK(n={self.n}; already promised {self.last_n})"
        return f"{self.src}->{self.dst}  {self.kind}"


# --------------------------------------------------------------------------
# Acceptor — the memory of the system
# --------------------------------------------------------------------------

class Acceptor:
    """An acceptor votes. It never talks to another acceptor.

    It keeps exactly three facts, and in production all three MUST survive a
    crash (fsync before replying). `durable=False` models the classic bug of
    keeping them in memory only — see experiments.py, experiment 5.
    """

    def __init__(self, node_id: str, durable: bool = True):
        self.id = node_id
        self.durable = durable
        self.alive = True
        self.promised_n: int = 0            # highest PREPARE answered
        self.accepted_n: int = 0            # highest proposal accepted
        self.accepted_value: Any = None     # the value that went with it

    # -- failure injection -------------------------------------------------
    def crash(self) -> None:
        self.alive = False
        if not self.durable:
            # It "forgot" — its promises were only ever in RAM.
            self.promised_n = 0
            self.accepted_n = 0
            self.accepted_value = None

    def recover(self) -> None:
        self.alive = True

    # -- protocol ----------------------------------------------------------
    def receive(self, msg: Message) -> List[Message]:
        if msg.kind == "PREPARE":
            if msg.n > self.promised_n:
                # Promise: "I will ignore anything numbered below n from now on."
                self.promised_n = msg.n
                return [Message(self.id, msg.src, "PROMISE", n=msg.n,
                                last_n=self.accepted_n,
                                last_value=self.accepted_value)]
            return [Message(self.id, msg.src, "NACK", n=msg.n, last_n=self.promised_n)]

        if msg.kind == "ACCEPT":
            # >= not > : the proposer that got our promise at exactly n may proceed.
            if msg.n >= self.promised_n:
                self.promised_n = msg.n
                self.accepted_n = msg.n
                self.accepted_value = msg.value
                return [Message(self.id, msg.src, "ACCEPTED", n=msg.n, value=msg.value)]
            return [Message(self.id, msg.src, "NACK", n=msg.n, last_n=self.promised_n)]

        return []

    def __repr__(self) -> str:
        state = "down" if not self.alive else "up"
        acc = "-" if self.accepted_value is None else f"{self.accepted_value!r}@{self.accepted_n}"
        return f"<{self.id} {state} promised={self.promised_n} accepted={acc}>"


# --------------------------------------------------------------------------
# Proposer — drives one attempt to get a value chosen
# --------------------------------------------------------------------------

class Proposer:
    """A proposer runs the two phases. Any number of them may run at once.

    The rule that makes Paxos work lives in `_pick_value`: a proposer does NOT
    get to insist on its own value. If any acceptor reports an already-accepted
    value, the proposer must abandon its own idea and re-propose that one.
    """

    def __init__(self, pid: str, rank: int, value: Any, acceptor_ids: List[str]):
        self.id = pid
        self.rank = rank                    # makes proposal numbers globally unique
        self.own_value = value
        self.acceptor_ids = list(acceptor_ids)
        self.round = 0
        self.n = 0
        self.phase = "idle"                 # idle | prepare | accept | done
        self.promises: Dict[str, Tuple[int, Any]] = {}
        self.accepts: Set[str] = set()
        self.proposed_value: Any = None     # what it actually ended up proposing
        self.adopted_from: Optional[int] = None  # set if it had to abandon own_value
        self.highest_nack: int = 0

    def next_number(self) -> int:
        """Unique, increasing proposal numbers: round*10 + rank.

        The rank digit guarantees two proposers can never pick the same number.
        Real systems use the same trick with (round, server_id) pairs.
        """
        self.round += 1
        return self.round * 10 + self.rank

    # -- phase 1 -----------------------------------------------------------
    def propose(self, n: Optional[int] = None) -> List[Message]:
        self.n = self.next_number() if n is None else n
        self.phase = "prepare"
        self.promises = {}
        self.accepts = set()
        self.proposed_value = None
        self.adopted_from = None
        return [Message(self.id, a, "PREPARE", n=self.n) for a in self.acceptor_ids]

    def retry(self) -> List[Message]:
        """Try again above the highest number that has rejected us."""
        floor = max(self.n, self.highest_nack)
        self.round = max(self.round, floor // 10)
        return self.propose()

    # -- value selection: the heart of the algorithm ------------------------
    def _pick_value(self) -> Any:
        best_n, best_value = 0, None
        for last_n, last_value in self.promises.values():
            if last_value is not None and last_n > best_n:
                best_n, best_value = last_n, last_value
        if best_value is None:
            return self.own_value           # nobody had accepted anything: free choice
        self.adopted_from = best_n          # somebody had: we must carry THEIR value
        return best_value

    # -- phase 2 -----------------------------------------------------------
    def receive(self, msg: Message) -> List[Message]:
        if msg.kind == "PROMISE" and msg.n == self.n and self.phase == "prepare":
            self.promises[msg.src] = (msg.last_n, msg.last_value)
            if len(self.promises) == quorum_size(len(self.acceptor_ids)):
                self.proposed_value = self._pick_value()
                self.phase = "accept"
                return [Message(self.id, a, "ACCEPT", n=self.n, value=self.proposed_value)
                        for a in self.acceptor_ids]

        elif msg.kind == "ACCEPTED" and msg.n == self.n:
            self.accepts.add(msg.src)
            if len(self.accepts) >= quorum_size(len(self.acceptor_ids)):
                self.phase = "done"

        elif msg.kind == "NACK":
            self.highest_nack = max(self.highest_nack, msg.last_n)

        return []

    def __repr__(self) -> str:
        return (f"<{self.id} n={self.n} phase={self.phase} "
                f"promises={len(self.promises)} accepts={len(self.accepts)} "
                f"proposing={self.proposed_value!r}>")


# --------------------------------------------------------------------------
# Cluster — the schedule is yours
# --------------------------------------------------------------------------

class Cluster:
    """Acceptors, proposers, and an in-memory network you drive by hand."""

    NAMES = "ABCDEFG"

    def __init__(self, num_acceptors: int = 5, seed: int = 0,
                 durable: bool = True, trace: bool = False):
        self.acceptors: Dict[str, Acceptor] = {
            self.NAMES[i]: Acceptor(self.NAMES[i], durable) for i in range(num_acceptors)
        }
        self.proposers: Dict[str, Proposer] = {}
        self.queue: List[Message] = []
        self.rng = random.Random(seed)
        self.drop_rate = 0.0
        self.dup_rate = 0.0
        self.trace = trace
        self.log: List[str] = []

        # what a learner would observe
        self.accepted_by: Dict[Tuple[int, Any], Set[str]] = {}
        self.chosen: List[Tuple[int, Any]] = []
        self.chosen_quorum: Optional[Set[str]] = None

    # -- setup -------------------------------------------------------------
    @property
    def quorum(self) -> int:
        return quorum_size(len(self.acceptors))

    def add_proposer(self, pid: str, value: Any) -> Proposer:
        p = Proposer(pid, len(self.proposers) + 1, value, list(self.acceptors))
        self.proposers[pid] = p
        return p

    def propose(self, pid: str, n: Optional[int] = None) -> None:
        self.queue.extend(self.proposers[pid].propose(n))

    def retry(self, pid: str) -> None:
        self.queue.extend(self.proposers[pid].retry())

    # -- driving the network ----------------------------------------------
    def _say(self, line: str) -> None:
        self.log.append(line)
        if self.trace:
            print(line)

    def deliver_next(self) -> bool:
        """Deliver the oldest in-flight message. False when nothing is pending."""
        if not self.queue:
            return False
        msg = self.queue.pop(0)
        if self.drop_rate and self.rng.random() < self.drop_rate:
            self._say(f"  x  LOST      {msg}")
            return True
        self._deliver(msg)
        if self.dup_rate and self.rng.random() < self.dup_rate:
            self._say(f"  =  DUPLICATE {msg}")
            self._deliver(msg)
        return True

    def _find(self, dst: str, kind: str, n: Optional[int]) -> Optional[int]:
        for i, m in enumerate(self.queue):
            if m.dst == dst and m.kind == kind and (n is None or m.n == n):
                return i
        return None

    def deliver_to(self, dst: str, kind: str, n: Optional[int] = None) -> bool:
        """Deliver the first in-flight message matching dst+kind (+n). Lets you
        build an exact schedule by hand — that is how you reproduce a specific
        race instead of hoping to stumble into it."""
        i = self._find(dst, kind, n)
        if i is None:
            return False
        self._deliver(self.queue.pop(i))
        return True

    def drop_to(self, dst: str, kind: str, n: Optional[int] = None) -> bool:
        i = self._find(dst, kind, n)
        if i is None:
            return False
        self._say(f"  x  LOST      {self.queue.pop(i)}")
        return True

    def deliver_many(self, dsts: str, kind: str, n: Optional[int] = None) -> None:
        """deliver_many('ABC', 'PREPARE') — one call per acceptor, in order."""
        for d in dsts:
            self.deliver_to(d, kind, n)

    def _deliver(self, msg: Message) -> None:
        node = self.acceptors.get(msg.dst) or self.proposers.get(msg.dst)
        if node is None:
            return
        if isinstance(node, Acceptor) and not node.alive:
            self._say(f"  o  {msg.dst} IS DOWN, message lost: {msg}")
            return
        self._say(f"  -> {msg}")
        for reply in node.receive(msg):
            if reply.kind == "ACCEPTED":
                self._record_accept(reply.src, reply.n, reply.value)
            self.queue.append(reply)

    def run(self, max_steps: int = 500) -> int:
        steps = 0
        while steps < max_steps and self.deliver_next():
            steps += 1
        return steps

    # -- learner -----------------------------------------------------------
    def _record_accept(self, acceptor_id: str, n: int, value: Any) -> None:
        seen = self.accepted_by.setdefault((n, value), set())
        seen.add(acceptor_id)
        if len(seen) == self.quorum:
            self.chosen.append((n, value))
            if self.chosen_quorum is None:
                self.chosen_quorum = set(seen)
            self._say(f"  *  CHOSEN {value!r} (n={n}) by majority {sorted(seen)}")

    def chosen_values(self) -> Set[Any]:
        return {v for _, v in self.chosen}

    def safety_holds(self) -> bool:
        """THE invariant: at most one value is ever chosen, for all time."""
        return len(self.chosen_values()) <= 1

    # -- inspection --------------------------------------------------------
    def crash(self, *ids: str) -> None:
        for i in ids:
            self.acceptors[i].crash()
            self._say(f"  !  {i} CRASHED")

    def recover(self, *ids: str) -> None:
        for i in ids:
            self.acceptors[i].recover()
            self._say(f"  +  {i} RECOVERED {self.acceptors[i]!r}")

    def table(self) -> str:
        head = f"{'acceptor':>9} | {'up?':^4} | {'promised':>8} | accepted"
        rows = [head, "-" * len(head)]
        for a in self.acceptors.values():
            acc = "-" if a.accepted_value is None else f"{a.accepted_value!r} (n={a.accepted_n})"
            rows.append(f"{a.id:>9} | {'up' if a.alive else 'DOWN':^4} | {a.promised_n:>8} | {acc}")
        return "\n".join(rows)
