#!/bin/bash
# Runner for the java-virtual-threads lab. Requires JDK 21+ (JDK 24+ to see
# the JEP 491 un-pinning behavior). No build step: single-file source launch.
set -e
cd "$(dirname "$0")"

case "${1:-help}" in
  cheap)      # Demo 1 — 100k virtual threads vs 5k platform threads
    java src/CheapThreads.java ;;
  carriers)   # Demo 2 — watch virtual threads hop between 2 carriers
    java -Djdk.virtualThreadScheduler.parallelism=2 src/CarrierHopping.java ;;
  throughput) # Demo 3 — blocking tasks: fixed pool vs virtual. Args: [tasks] [blockMs]
    java src/ThroughputRace.java "${2:-600}" "${3:-50}" ;;
  pinning)    # Demo 4 — synchronized + 1 carrier: pinned (<=23) vs not (24+)
    java -Djdk.virtualThreadScheduler.parallelism=1 src/PinningStory.java ;;
  cpu)        # Demo 5 — virtual threads don't speed up CPU-bound work
    java src/CpuBoundTrap.java ;;
  test)       # Scenario tests, on a single carrier so scheduling claims are real
    java -Djdk.virtualThreadScheduler.parallelism=1 src/ScenarioTests.java ;;
  all)
    for demo in cheap carriers throughput pinning cpu test; do
      echo "==================== $demo ===================="; "$0" "$demo"; echo
    done ;;
  *)
    echo "usage: ./run.sh {cheap|carriers|throughput|pinning|cpu|test|all}"
    grep -E '^  [a-z]+\)' "$0" | sed 's/)[[:space:]]*#/  —/' ;;
esac
