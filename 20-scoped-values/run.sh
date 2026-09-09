#!/bin/bash
# Runner for the scoped-values lab. ScopedValue is FINAL in JDK 25 (JEP 506):
# most demos need no flags. The preview flags appear only where
# StructuredTaskScope (still preview) is involved.
set -e
cd "$(dirname "$0")"

case "${1:-help}" in
  basic)    # Demo 1 — bind at the top, read three layers down
    java src/BasicFlow.java ;;
  rebind)   # Demo 2 — shadow a binding for a sub-call; auto-restore
    java src/Rebinding.java ;;
  leak)     # Demo 3 — the ThreadLocal disease this cures
    java src/ThreadLocalLeak.java ;;
  inherit)  # Demo 4 — structured forks inherit; bare threads don't
    java --enable-preview --source 25 src/Inheritance.java ;;
  test)     # 11 scenario tests
    java --enable-preview --source 25 src/ScenarioTests.java ;;
  all)
    for demo in basic rebind leak inherit test; do
      echo "==================== $demo ===================="; "$0" "$demo"; echo
    done ;;
  *)
    echo "usage: ./run.sh {basic|rebind|leak|inherit|test|all}"
    grep -E '^  [a-z]+\)' "$0" | sed 's/)[[:space:]]*#/  —/' ;;
esac
