#!/usr/bin/env bash
# Compile the lab and run one experiment.
#
#   ./run.sh Visibility plain
#   ./run.sh Reordering volatile
#   ./run.sh -Xint Visibility plain          <- JVM flags are passed through
#
set -uo pipefail
cd "$(dirname "$0")"

mkdir -p out
javac -d out src/*.java || { echo "compile failed"; exit 1; }

if [ $# -eq 0 ]; then
  echo "usage: ./run.sh [jvm-flags] <Experiment> [args]"
  echo "experiments: Visibility | Reordering | LostUpdate | Publication | DoubleCheckedLocking | Tests"
  exit 2
fi

exec java -cp out "$@"
