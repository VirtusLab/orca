# Example 01 — simple in-memory flow

The minimum-viable Orca flow: ask the agent to plan, then implement
each task in sequence with a review-and-fix loop.

The plan lives entirely in memory for one run — there's no plan file,
no resume, no on-disk state beyond the git commits the flow produces.
Use this when the task is small enough to finish in one session.

## What it does

1. **Plan.** The agent receives the user's prompt and returns a
   structured `Plan(tasks: List[Task])` (one interactive turn).
2. **Surface.** `plan.logTo` shows the plan via the event bus.
3. **For each task:**
   - Create the task's branch.
   - Hand the task's prompt to the agent (continuing the planning
     session).
   - Commit.
   - Run `reviewAndFixLoop` with `defaultReviewers(claude)`.

## Prerequisites

- JDK 21+, [scala-cli](https://scala-cli.virtuslab.org/).
- `claude` logged in (see the repo root README).
- `com.virtuslab::orca:0.1.0-SNAPSHOT` published locally:

  ```bash
  cd <orca-sandbox>
  sbt publishLocal
  ```

- A target project to run against. The seed script
  [`examples/create-test-project.sh`](../create-test-project.sh)
  creates a tiny Java project for smoke-testing:

  ```bash
  ./examples/create-test-project.sh /tmp/orca-demo
  cd /tmp/orca-demo
  ```

## Run

```bash
scala-cli run <path-to-orca>/examples/01-simple/ship.sc -- \
  "Add a multiply method to Calculator"
```

Use a small lint command for the review step (the script wires
`mvn -q test`); swap it for `sbt test`, `pytest -q`, or whatever your
project uses.
