#!/usr/bin/env bash
#
# Seeds the todo-cli scratch project for example 03-epic into a temp
# directory (or a path you supply) and inits a git repo. The starter
# is intentionally feature-incomplete (no persistence, no done/delete
# commands, no priorities) so the epic prompt below decomposes into
# several distinct tasks rather than collapsing into one.
#
# Usage:
#   examples/03-epic/create-test-project.sh                    # mktemp, Maven Central
#   examples/03-epic/create-test-project.sh /path/to/dir       # explicit dest
#   examples/03-epic/create-test-project.sh --local            # publishLocal + pin
#   examples/03-epic/create-test-project.sh --local /path/...  # both

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEED_DIR="$SCRIPT_DIR/test-project"
# Flow scripts live in the top-level `plans/` directory so the test-project
# folders stay free of orca-runtime artefacts.
PLANS_DIR="$(cd "$SCRIPT_DIR/../../plans" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# shellcheck source=../_seed_lib.sh
. "$SCRIPT_DIR/../_seed_lib.sh"

parse_args "$@"
resolve_dest "orca-03-epic"
init_destination "$SEED_DIR" "$PLANS_DIR" "epic.sc" "Initial todo-cli project"
apply_local_flag "$REPO_ROOT" "$DEST/epic.sc"

cat <<EOF

Test project ready at: $DEST

Next steps:
  cd $DEST
  scala-cli run epic.sc -- \\
    "Persist tasks to a JSON file at ~/.todo/tasks.json (load on startup, save on every change), \\
     add 'done <id>' and 'delete <id>' commands, and support priority levels (low/medium/high) \\
     with a 'list --priority' filter"
EOF
