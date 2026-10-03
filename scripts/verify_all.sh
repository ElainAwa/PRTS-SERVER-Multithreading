#!/usr/bin/env bash
# PRTS structural gate: the package graph of the two source trees against the layer rules and the
# frozen baseline. Paths are resolved from this script, so it can be run from anywhere.
#
# usage: scripts/verify_all.sh [--self-test] [--top N]
#   --self-test   also run the same command over scripts/verify/fixture, a tree that carries one
#                 deliberate edge from the kernel to a domain; that run must fail. A gate that
#                 cannot fail on a broken tree proves nothing about a good one.
#   --top N       how many entries of each list to print (default 5, 0 prints all)
#
# policy:
#   * a package may depend on a package of a lower or equal rank, never on a higher one;
#   * the layer rules are scripts/verify/layer-rules-prts.txt, the known debt is
#     scripts/verify/baseline-prts.txt, and only a repaid entry may be removed from the baseline;
#   * a new cycle or a new layer crossing fails this gate.
#
# exit code: 0 when the tree carries no new cycle and no new layer crossing, 1 otherwise.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
VERIFY="$HERE/verify"
CHECKER="$VERIFY/check_package_graph.py"
RULES="$VERIFY/layer-rules-prts.txt"
BASELINE="$VERIFY/baseline-prts.txt"
SOURCES="$ROOT/arclight-common/src/main/java,$ROOT/arclight-neoforge/src/main/java"
FIXTURE="$VERIFY/fixture"

SELF_TEST=0
TOP=5
while [ "$#" -gt 0 ]; do
    case "$1" in
        --self-test) SELF_TEST=1 ;;
        --top) shift; TOP="${1:?--top needs a number}" ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
    shift
done

if ! command -v python3 >/dev/null 2>&1; then
    echo "verify_all: python3 is required for the package graph check" >&2
    exit 2
fi
for file in "$CHECKER" "$RULES" "$BASELINE"; do
    if [ ! -f "$file" ]; then
        echo "verify_all: missing $file" >&2
        exit 2
    fi
done

if [ "$SELF_TEST" -eq 1 ]; then
    echo "== self test: the fixture must be refused"
    python3 "$CHECKER" --roots "$FIXTURE" --baseline "$BASELINE" --layer-rules "$RULES" \
        --top "$TOP" >/dev/null 2>&1
    fixture_exit=$?
    if [ "$fixture_exit" -eq 0 ]; then
        echo "verify_all: FAILED — the deliberate kernel -> domain edge was accepted" >&2
        exit 1
    fi
    echo "== self test: refused as expected (exit $fixture_exit)"
fi

echo "== package graph: arclight-common + arclight-neoforge"
python3 "$CHECKER" --roots "$SOURCES" --baseline "$BASELINE" --layer-rules "$RULES" --top "$TOP"
exit $?
