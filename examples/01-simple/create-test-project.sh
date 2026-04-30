#!/usr/bin/env bash
#
# Seeds the calculator Rust crate for example 01-simple into a temp
# directory (or a path you supply) and inits a git repo so the flow
# has something to commit against. The seed files live in the sibling
# `test-project/` directory — edit those, not the script, if you want
# the starter to look different.
#
# Usage:
#   examples/01-simple/create-test-project.sh                # mktemp
#   examples/01-simple/create-test-project.sh /path/to/dir   # explicit dest

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEED_DIR="$SCRIPT_DIR/test-project"

DEST="${1:-}"
if [[ -z "$DEST" ]]; then
  DEST="$(mktemp -d -t orca-01-simple-XXXXXX)"
else
  mkdir -p "$DEST"
fi

# `cp -R src/.` copies the directory's *contents*, including dotfiles
# (.gitignore here) but not the wrapping `test-project` dir itself.
cp -R "$SEED_DIR/." "$DEST/"

cd "$DEST"
git init -q -b main
git -c user.name=orca-seed -c user.email=orca-seed@example.com add . > /dev/null
git -c user.name=orca-seed -c user.email=orca-seed@example.com \
    commit -q -m "Initial calculator crate"

cat <<EOF

Test project ready at: $DEST

Next steps:
  cd $DEST
  scala-cli run implement.sc -- "Add a multiply function to the calculator crate"
EOF
