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
#   examples/02-bugfix/create-test-project.sh                    # mktemp, Maven Central
#   examples/02-bugfix/create-test-project.sh /path/to/dir       # explicit dest
#   examples/02-bugfix/create-test-project.sh --local            # publishLocal + pin
#   examples/02-bugfix/create-test-project.sh --local /path/...  # both

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
resolve_dest "orca-02-bugfix"
init_destination "$SEED_DIR" "$PLANS_DIR" "bugfix.sc" "Initial buggy calculator project"
apply_local_flag "$REPO_ROOT" "$DEST/bugfix.sc"

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
