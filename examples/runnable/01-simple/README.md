# Example 01 — simple in-memory flow (autonomous planning)

The minimum-viable Orca flow: ask the agent to plan in one turn, then
implement each task in sequence with a review-and-fix loop.

The plan lives entirely in memory for one run — there's no plan file,
no resume, no on-disk state beyond the git commits the flow produces.
Use this when the task is small enough to finish in one session and
concrete enough that the planner doesn't need to ask anything. For
the variant where the planner can ask clarifying questions, see
[02-interactive](../02-interactive/).

## What it does

1. **Plan.** The agent receives the user's prompt and returns a
   structured `Plan(tasks: List[Task])` in a single autonomous turn.
   The `Announce[Plan]` instance auto-summarises it on the event bus —
   no explicit `plan.announce` call needed.
2. **For each task:**
   - Create the task's branch.
   - Hand the task's prompt to the agent (continuing the planning
     session).
   - Commit.
   - Run `reviewAndFixLoop` with `allReviewers(claude)`.

## Prerequisites

- JDK 21+, [scala-cli](https://scala-cli.virtuslab.org/).
- `claude` logged in (see the repo root README).
- `cargo` on PATH — the seed is a small Rust crate, so first-run
  auto-discovery resolves cargo commands into
  `.orca/settings.properties`, which the review loop reads by
  default. To override the lint gate per call in `implement.sc`, pass
  `lint = Configured.Use(Lint(List("..."), agent.cheap))`.
- A target project to run against. The sibling
  [`create-test-project.sh`](create-test-project.sh) seeds a
  tiny Rust calculator crate from `test-project/` and copies the
  flow script — [`implement.sc`](../../implement.sc) —
  into the same temp dir, then inits git:

  ```bash
  ./examples/runnable/01-simple/create-test-project.sh
  # → "Test project ready at: /tmp/orca-01-simple-…"
  ```

  Edit `test-project/` for a different starter, or
  [`implement.sc`](../../implement.sc) for a different
  flow.

## Run

The seed script prints the exact `scala-cli run` line. Because
`implement.sc` is copied into the temp dir alongside the crate,
the invocation is just the file name — no `<path-to-orca>` chasing:

```bash
cd /tmp/orca-01-simple-…
scala-cli run implement.sc -- "Add a multiply function to the calculator crate"
```
