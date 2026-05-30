#!/usr/bin/env bash
#
# Seeds the Scala calculator scratch project for example 03-bugfix into a
# temp directory (or a path you supply) and inits a git repo. Includes a
# minimal `.github/workflows/ci.yml` so the bugfix flow's "wait for CI"
# stage has something to observe once the project is pushed to GitHub.
#
# The flow takes a GitHub issue ref and opens a PR via `gh`, so the seeded
# project has to live on a real GitHub repo *and* the bug must exist as
# an issue there — see "Next steps" at the end of this script.
#
# Usage:
#   examples/03-bugfix/create-test-project.sh                    # mktemp, Maven Central
#   examples/03-bugfix/create-test-project.sh /path/to/dir       # explicit dest
#   examples/03-bugfix/create-test-project.sh --local            # publishLocal + pin
#   examples/03-bugfix/create-test-project.sh --local /path/...  # both
# (--run is not supported here: the flow needs a GitHub repo + issue first.)

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
resolve_dest "orca-03-bugfix"
init_destination "$SEED_DIR" "$PLANS_DIR" "issue-pr-bugfix.sc" "Initial buggy calculator project"
apply_local_flag "$REPO_ROOT" "$DEST/issue-pr-bugfix.sc"

warn_run_unsupported "the flow needs a GitHub repo + issue first (see below)"

cat <<EOF

Test project ready at: $DEST

Example 03 needs a real GitHub repo (so the flow can open a PR and watch
CI) and a real issue on that repo (the flow takes an issue ref as input).
Push and file an issue first:

  cd $DEST
  gh repo create <your-name>/orca-bugfix-demo --source=. --private --push
  gh issue create \\
    --title "Calculator.add silently overflows on Int.MinValue" \\
    --body  "Calling Calculator.add(Int.MinValue, -1) returns Int.MaxValue instead of throwing."

Note the issue number gh prints, then drive the flow from the same dir:

  scala-cli run issue-pr-bugfix.sc -- "<your-name>/orca-bugfix-demo#<n>"
EOF
