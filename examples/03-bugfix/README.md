# Example 03 — issue-pr bugfix flow

Issue-driven, red-test-first bugfix loop for a Scala project. The script
takes a GitHub issue ref, triages whether it's actually a bug, lands a
failing test on a fresh branch, opens a PR, lets CI confirm the failure
is real, and only then writes the fix.

## Why issue-driven + red-test-first?

The flow makes three structured decisions up front, so noise gets
filtered before any code lands:

1. *Is this actually a bug?* "Not intended behavior" or "user error"
   reports get a comment back on the issue and the flow stops — no
   branch, no PR.
2. *Can a focused unit test reproduce it?* If yes, write the test. If
   not (UI-only, races), post reproduction steps on the issue and
   stop — the PR is meaningless when there's nothing executable to
   carry.
3. *Did the right thing go red in CI?* After pushing the failing
   test, the flow inspects the failed run and verifies the failure
   matches the original report. A mismatch fails loudly.

Only after those three gates does the implementation start.

## Stages

1. **Read issue** — `gh.readIssue`.
2. **Triage** *(opus, interactive)* — agent returns a
   `BugTriage(isBug, notBugExplanation, canTest, reproductionSteps,
   failingTestPath, branchName, summary)`.
3. **Bail-out paths** — `!isBug` or `isBug && !canTest` post a comment
   on the issue and stop.
4. **Write the failing test** — autonomous turn, same session. Committed.
5. **Push branch + open PR** — `gh.createPr` with a tentative
   description folded by `summarisePr(claude.haiku)`.
6. **Wait for CI red** — `gh.waitForBuild` (30 min). Green here is a
   reproduction failure.
7. **Post focused failure comment** *(sonnet)* — sonnet inspects the
   failed run via `gh` directly and writes the comment. The flow
   never pulls the log into memory.
8. **Verify failure matches the report** *(sonnet)* — same gh-driven
   inspection, structured `BugReportMatch` verdict.
9. **Plan the fix** — `Plan.autonomous.from`, persisted under
   `.orca/plan-<hash>.md` so a crash resumes.
10. **Implement the fix** — `Plan.implementTaskLoop` runs each task
    through implementation, `sbt scalafmtAll`, and `reviewAndFixLoop`
    with `sbt test` as the lint command.
11. **Push the fix** — no final CI wait; a human picks the PR up.

## Prerequisites

- JDK 21+, scala-cli, `claude` logged in (see repo root README).
- `gh` authenticated against the target repo.
- The repo has the included `.github/workflows/ci.yml` (sbt-based)
  or any other workflow that runs the test suite.

## Seeded test project

The sibling [`create-test-project.sh`](create-test-project.sh) copies
a tiny Scala calculator (`add` and `subtract` that silently overflow
on `Int.MinValue`) plus a minimal sbt CI workflow from
[`test-project/`](test-project/) into a temp dir, then drops the flow
script — [`plans/issue-pr-bugfix.sc`](../../plans/issue-pr-bugfix.sc)
— alongside it. The seed script prints `gh repo create` and
`gh issue create` lines you can copy-paste so the flow has an issue
to triage and a repo to push to.

## Run

```bash
./examples/03-bugfix/create-test-project.sh
# follow the printed gh repo create / gh issue create lines, then:
cd /tmp/orca-03-bugfix-…
scala-cli run issue-pr-bugfix.sc -- "<your-name>/orca-bugfix-demo#<n>"
```

The flow's lint command is `sbt test`; format is `sbt scalafmtAll`.
Swap either in the script if your project differs.

## What the agent sees

For each stage Orca passes a single, focused prompt. Triage sees the
issue body; "write the failing test" gets a target path; the sonnet
turns get only the PR ref and are told to use `gh` themselves to
inspect the failed run (the log is never embedded in the prompt).
The flow script is plumbing — adapting any of these prompts is a
one-line change.
