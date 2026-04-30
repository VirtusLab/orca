#!/usr/bin/env bash
#
# Seeds the calculator scratch project for example 02-bugfix into a temp
# directory (or a path you supply) and inits a git repo. Includes a
# minimal `.github/workflows/ci.yml` so the bugfix flow's "wait for CI"
# stages have something to observe once the project is pushed to GitHub.
#
# The flow itself opens a PR via `gh`, so the seeded project has to live
# on a real GitHub repo — see "Next steps" at the end of this script.
#
# Usage:
#   examples/02-bugfix/create-test-project.sh                # mktemp
#   examples/02-bugfix/create-test-project.sh /path/to/dir   # explicit dest

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEED_DIR="$SCRIPT_DIR/test-project"

DEST="${1:-}"
if [[ -z "$DEST" ]]; then
  DEST="$(mktemp -d -t orca-02-bugfix-XXXXXX)"
else
  mkdir -p "$DEST"
fi

cp -R "$SEED_DIR/." "$DEST/"

cd "$DEST"
git init -q -b main
git -c user.name=orca-seed -c user.email=orca-seed@example.com add . > /dev/null
git -c user.name=orca-seed -c user.email=orca-seed@example.com \
    commit -q -m "Initial buggy calculator project"

cat <<EOF

Test project ready at: $DEST

Example 02 needs a real GitHub repo so the flow can open a PR and wait
for CI. Push the seed somewhere first:

  cd $DEST
  gh repo create <your-name>/orca-bugfix-demo --source=. --private --push

Then drive the flow from the same working directory:

  scala-cli run bugfix.sc -- \\
    "Calculator.add overflows when one argument is Integer.MIN_VALUE"
EOF
