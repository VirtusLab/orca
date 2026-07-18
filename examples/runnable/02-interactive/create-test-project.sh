#!/usr/bin/env bash
#
# Seeds the calculator Rust crate for example 02-interactive into a
# temp directory (or a path you supply) and inits a git repo so the
# flow has something to commit against. The seed files live in the
# sibling `test-project/` directory — edit those, not the script, if
# you want the starter to look different.
#
# The companion flow (`flows/implement-interactive.sc`) uses
# `Plan.interactive.from`, so the planner can call the `ask_user` MCP
# tool to clarify an underspecified prompt before producing the plan.
#
# Usage:
#   examples/runnable/02-interactive/create-test-project.sh                    # mktemp, Maven Central
#   examples/runnable/02-interactive/create-test-project.sh /path/to/dir       # explicit dest
#   examples/runnable/02-interactive/create-test-project.sh --local            # publishLocal + pin
#   examples/runnable/02-interactive/create-test-project.sh --run              # seed, then run it
#   examples/runnable/02-interactive/create-test-project.sh --settings         # seed stack settings too
#   examples/runnable/02-interactive/create-test-project.sh --local /path/...  # combinable

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEED_DIR="$SCRIPT_DIR/test-project"
# Flow scripts live in the top-level `flows/` (three levels up) so the
# test-project folders stay free of orca-runtime artefacts. Resolved relative
# to this script so a checkout in any location still works.
FLOWS_DIR="$(cd "$SCRIPT_DIR/../../../flows" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

# shellcheck source=../_seed_lib.sh
. "$SCRIPT_DIR/../_seed_lib.sh"

parse_args "$@"
resolve_dest "orca-02-interactive"
# Stack settings for the Rust calculator starter; header must match
# SettingsFile.Header so the runtime treats the file as its own output.
write_settings_file '# orca stack settings — edit freely, commit with the project.
# Delete this file to re-run auto-discovery.
# rustfmt ships with the Rust toolchain
format = cargo fmt
# compiles main and test code, runs nothing
lint = cargo check --tests
test = cargo test'
init_destination "$SEED_DIR" "$FLOWS_DIR" "implement-interactive.sc" "Initial calculator crate"
apply_local_flag "$REPO_ROOT" "$DEST/implement-interactive.sc"

PROMPT="Add a new arithmetic operation to the calculator crate. Ask the user which."

echo
echo "Test project ready at: $DEST"
maybe_run "implement-interactive.sc" "$PROMPT"  # execs scala-cli when --run
cat <<EOF

Next steps:
  cd $DEST
  scala-cli run implement-interactive.sc -- "$PROMPT"

The trailing "Ask the user which." pushes the planner to call ask_user
rather than guessing the operation. Type the answer at the prompt and
the conversation continues.
EOF
