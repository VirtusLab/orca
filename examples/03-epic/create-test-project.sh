#!/usr/bin/env bash
#
# Seeds the todo-cli scratch project for example 03-epic into a temp
# directory (or a path you supply) and inits a git repo. The starter
# is intentionally feature-incomplete (no persistence, no done/delete
# commands, no priorities) so the epic prompt below decomposes into
# several distinct tasks rather than collapsing into one.
#
# Usage:
#   examples/03-epic/create-test-project.sh                # mktemp
#   examples/03-epic/create-test-project.sh /path/to/dir   # explicit dest

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEED_DIR="$SCRIPT_DIR/test-project"

DEST="${1:-}"
if [[ -z "$DEST" ]]; then
  DEST="$(mktemp -d -t orca-03-epic-XXXXXX)"
else
  mkdir -p "$DEST"
fi

cp -R "$SEED_DIR/." "$DEST/"

cd "$DEST"
git init -q -b main
git -c user.name=orca-seed -c user.email=orca-seed@example.com add . > /dev/null
git -c user.name=orca-seed -c user.email=orca-seed@example.com \
    commit -q -m "Initial todo-cli project"

cat <<EOF

Test project ready at: $DEST

Next steps:
  cd $DEST
  scala-cli run $SCRIPT_DIR/epic.sc -- \\
    "Persist tasks to a JSON file at ~/.todo/tasks.json (load on startup, save on every change), \\
     add 'done <id>' and 'delete <id>' commands, and support priority levels (low/medium/high) \\
     with a 'list --priority' filter"
EOF
