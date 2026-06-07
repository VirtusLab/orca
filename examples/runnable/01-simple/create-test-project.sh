#!/usr/bin/env bash
#
# Seeds the calculator Rust crate for example 01-simple into a temp
# directory (or a path you supply) and inits a git repo so the flow
# has something to commit against. The seed files live in the sibling
# `test-project/` directory — edit those, not the script, if you want
# the starter to look different.
#
# Usage:
#   examples/runnable/01-simple/create-test-project.sh                    # mktemp, Maven Central
#   examples/runnable/01-simple/create-test-project.sh /path/to/dir       # explicit dest
#   examples/runnable/01-simple/create-test-project.sh --local            # publishLocal + pin
#   examples/runnable/01-simple/create-test-project.sh --run              # seed, then run it
#   examples/runnable/01-simple/create-test-project.sh --local /path/...  # combinable

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEED_DIR="$SCRIPT_DIR/test-project"
# Flow scripts live in `examples/` (two levels up) so the test-project folders
# stay free of orca-runtime artefacts. Resolved relative to this script so a
# checkout in any location still works.
FLOWS_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

# shellcheck source=../_seed_lib.sh
. "$SCRIPT_DIR/../_seed_lib.sh"

parse_args "$@"
resolve_dest "orca-01-simple"
init_destination "$SEED_DIR" "$FLOWS_DIR" "implement.sc" "Initial calculator crate"
apply_local_flag "$REPO_ROOT" "$DEST/implement.sc"

PROMPT="Add a multiply function to the calculator crate"

echo
echo "Test project ready at: $DEST"
maybe_run "implement.sc" "$PROMPT"  # execs scala-cli when --run; else no-op
cat <<EOF

Next steps:
  cd $DEST
  scala-cli run implement.sc -- "$PROMPT"
EOF
