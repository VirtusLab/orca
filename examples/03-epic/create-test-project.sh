#!/usr/bin/env bash
#
# Seeds the calculator scratch project for example 03-epic into a temp
# directory (or a path you supply) and inits a git repo. The epic flow
# generates `epic.md` on first run and resumes from it on re-runs, so
# the seed itself stays plain — same shape as the 01-simple starter.
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
    commit -q -m "Initial calculator project"

cat <<EOF

Test project ready at: $DEST

Next steps:
  cd $DEST
  scala-cli run $SCRIPT_DIR/epic.sc -- \\
    "Add a divide method to Calculator with full test coverage"
EOF
