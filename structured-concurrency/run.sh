#!/bin/bash
# Runner for the structured-concurrency lab. Requires JDK 25 (the
# StructuredTaskScope API is a PREVIEW feature — JEP 505 — hence the flags).
set -e
cd "$(dirname "$0")"
J="java --enable-preview --source 25"

case "${1:-help}" in
  orphan)   # Demo 0 — the OLD world: a failed fan-out leaks an orphan task
    java src/OrphanLeak.java ;;
  fanout)   # Demo 1 — fork / join / combine: the basic shape
    $J src/FanOut.java ;;
  failfast) # Demo 2 — one child fails -> siblings cancelled, ~60ms not 5s
    $J src/FailFast.java ;;
  race)     # Demo 3 — anySuccessful: first replica wins, loser cancelled
    $J src/RaceReplicas.java ;;
  deadline) # Demo 4 — one timeout for the whole scope
    $J src/Deadline.java ;;
  test)     # Scenario tests: every README rule, asserted
    $J src/ScenarioTests.java ;;
  all)
    for demo in orphan fanout failfast race deadline test; do
      echo "==================== $demo ===================="; "$0" "$demo"; echo
    done ;;
  *)
    echo "usage: ./run.sh {orphan|fanout|failfast|race|deadline|test|all}"
    grep -E '^  [a-z]+\)' "$0" | sed 's/)[[:space:]]*#/  —/' ;;
esac
