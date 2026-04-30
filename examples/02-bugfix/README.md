# Example 02 — bugfix flow

Red-test-first bugfix loop that exercises GitHub. The script turns a
bug report into a failing test, opens a PR, lets CI confirm the
failure is real, and only then writes the fix.

## Why "red-test-first"?

Two bugs deserve different rigour:

1. *I think this is a bug.* Write a focused test that demonstrates
   the misbehaviour. Push it; let CI agree.
2. *We can't test this in CI* (UI-only, race conditions, etc.).
   Document the steps to reproduce in `REPRODUCTION.md`. The PR is
   the artefact; the human signs off.

The flow handles both via a structured `BugTriage` decision the
agent makes up front.

## Stages

1. **Triage the bug** *(interactive)* — agent reads the report,
   explores the code, and returns a `BugTriage(canTest,
   reproductionSteps, failingTestPath, branchName, summary)`.
2. **Write the failing artefact** — either a unit test (preferred)
   or `REPRODUCTION.md`, on a fresh branch. Committed.
3. **Push and open PR** — `git.push`, `gh.createPr`.
4. **Wait for CI to fail** — `gh.waitForBuild`. If CI is green
   here, the agent's reproduction was wrong; the stage fails loudly
   so you re-triage.
5. **Comment on PR with the failure log** — `gh.writeComment`.
6. **Verify the failure matches the report** — `claude.resultAs[BugReportMatch]`
   on the same session, given the actual CI log. Mismatch → fail.
7. **Implement the fix** — autonomous continuation of the same
   session. `reviewAndFixLoop` polishes.
8. **Push the fix** — `git.push`.
9. **Wait for CI to pass** — final green check.

## Prerequisites

- JDK 21+, scala-cli, `claude` logged in (see repo root README).
- `gh` authenticated against the target repo.
- The repo has a CI workflow that runs the relevant test suite.
- `com.virtuslab::orca:0.1.0-SNAPSHOT` published locally (`sbt publishLocal`).

## Seeded test project

The sibling [`create-test-project.sh`](create-test-project.sh)
copies a Calculator project (with a naïve `add` that overflows on
`Integer.MIN_VALUE`), a minimal `.github/workflows/ci.yml`, and the
flow script itself — [`test-project/bugfix.sc`](test-project/bugfix.sc) —
from [`test-project/`](test-project/) into a temp dir. Push it to
a real GitHub repo (the script prints the `gh repo create` line)
so the flow can open a PR and watch CI.

## Run

```bash
cd /tmp/orca-02-bugfix-…   # the seed-script's temp dir
scala-cli run bugfix.sc -- \
  "Calculator.add returns Integer.MAX_VALUE when one input is Integer.MIN_VALUE"
```

The lint command in the script is `mvn -q test`; swap for whatever
matches the project.

## What the agent sees

For each stage Orca passes the agent a single, focused prompt.
Triage gets the bug report; "write the failing test" gets the
expected path; "verify failure matches" gets the CI log. The flow
script is just plumbing — adapting any of these prompts is a
one-line change.
