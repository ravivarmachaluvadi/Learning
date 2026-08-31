"""Stubs for EXERCISES.md rung 5 (Implement).

Both start as NotImplementedError, so `python3 -m unittest discover -s tests`
fails RED until you write them. Answers in SOLUTIONS.md — try first.
"""


def predicted_height(n_keys, max_keys):
    """Exercise 5.1 — the fanout maths (README §7).

    Given `n_keys` keys in a B+ tree whose pages hold at most `max_keys`
    keys, return the SMALLEST possible height (number of levels, root
    included) such a tree could have — i.e. if every page were completely
    full.

    Why this matters: it is the floor on read cost. A real tree is taller
    because pages carry slack (fill factor ~70%), which is exactly what
    tests/test_exercises.py checks — your number must be <= the real tree's
    height, and within a level or two of it.

    Examples:
        predicted_height(1, 4)   == 1    # one page, one level
        predicted_height(4, 4)   == 1    # still fits in the root leaf
        predicted_height(5, 4)   == 2    # must split: root + 2 leaves
        predicted_height(1000, 4) == 5
    """
    raise NotImplementedError("exercise 5.1")


def newest_wins_merge(tables):
    """Exercise 5.2 — the heart of compaction (README §10.6, invariant 4).

    `tables` is a list of (seq, entries) pairs in ARBITRARY order, where
    `entries` is a list of (key, value) pairs sorted by key. A value may be
    the string "<TOMBSTONE>" meaning "this key was deleted".

    The `seq` number — NOT the position in the list — says which table is
    newer (higher seq = newer). Relying on position is how real merge bugs
    happen; the tests check you didn't.

    Return the compacted result as a list of (key, value) pairs, sorted by
    key, where:
      * for each key, the value from the HIGHEST seq wins (newest wins), and
      * keys whose winning value is a tombstone are omitted entirely
        (compaction is where tombstones and shadowed values physically die).

    Example:
        newest_wins_merge([
            (1, [("a", "old"), ("b", "keep")]),
            (2, [("a", "new"), ("c", "<TOMBSTONE>")]),
        ]) == [("a", "new"), ("b", "keep")]

    Getting the priority backwards resurrects deleted data — which is exactly
    the planted bug in src/broken_lsm.py (rung 6).
    """
    raise NotImplementedError("exercise 5.2")
