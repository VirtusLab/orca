# Writing your first flow

This page builds the shipped `implement.sc` piece by piece. The flow plans a
prompt into tasks, implements and reviews each task, reviews the whole change,
and finally opens a PR.

## The header

Save the file as `implement.sc`. Every flow starts with the same header, which
pins the Scala version, the Orca dependency and the JVM:

```scala
//> using scala 3.9.0
//> using dep "org.virtuslab::orca:0.2.0"
//> using jvm 21

import orca.{*, given}
```

If the first line of the file is a `//` comment, `orca list` shows it as the
flow's description.

## The body

`flow(OrcaArgs(args))` parses the command line and runs the body. Inside the
body, `userPrompt` is the prompt given on the command line, and
`planningAgent`, `codingAgent` and `reviewAgent` are the role agents resolved
from [settings](../using/settings.md).

```scala
flow(OrcaArgs(args)):
  val plan = stage("Plan"):
    Plan.autonomous.from(userPrompt, planningAgent).value
```

`stage` is the committing, resumable unit of work. Here, the planner produces
the plan in a single turn. The result is recorded in the progress log, so if
you re-run the flow with the same prompt, this stage is skipped and the stored
plan is read back instead. `.value` drops the planning chat and keeps only the
`Plan`; see [Planning](planning.md) for what else you can do with the planner's
result.

## One stage per task

Next, we create one stage for each task in the plan:

```scala
  val taskOpenFindings =
    for task <- plan.tasks yield
      stage(s"Task: ${task.title}"):
        val session = codingAgent.session("implementer", seed = plan.brief)
        session.run(task.description)
        reviewThenFix(
          coderSession = session,
          reviewers = allReviewers(reviewAgent),
          task = task
        )
```

As with the planning stage, a re-run skips the tasks that are already
completed and picks up at the first incomplete one.

`codingAgent.session("implementer", seed = plan.brief)` creates a durable
conversation. A session is keyed by its name plus the stage it is created in,
so this loop creates one session per task without any extra naming. On first
use, the session is primed with the plan's brief, and if the harness loses the
conversation, it is started again from that seed. [Talking to
agents](talking-to-agents.md) describes sessions in detail.

`reviewThenFix` runs one review round followed by one fix turn. It returns the
findings that it left open, so that the final review below can be told about
them. See [Review and fix loops](review.md) for how reviewers are picked, and
how format and lint run.

## The final review

Each task ran only one review round, so nobody has checked the fixes
themselves. The final review loops over the whole change until the reviewers
are satisfied:

```scala
  val openFindings = stage("Final review"):
    val finalFixer = codingAgent.session("final-fixer", seed = plan.brief)
    reviewAndFixLoop(
      coderSession = finalFixer,
      reviewers = allReviewers(reviewAgent),
      task = Task(Title("The whole planned change"), plan.brief),
      diff = ReviewDiff.WholeRun,
      maxFixTurns = 5,
      priorOpenFindings = taskOpenFindings.flatMap(_.findings)
    )
```

This is a new session, because the per-task sessions are keyed to their own
stages; it is seeded the same way as they were. `Title` wraps a task title.
`ReviewDiff.WholeRun` shows the reviewers everything that changed since the run
started. `maxFixTurns = 5` is above the default of 3, because nothing reviews
the code after this loop. Finally, `priorOpenFindings` hands over what the
per-task reviews left open.

## Open a PR

The last step opens a pull request:

```scala
  openPrIfGitHub(
    summarisingAgent = codingAgent,
    openFindings = openFindings
  )
```

A PR is opened when the repository is on GitHub and `gh` can reach it.
Otherwise, the flow prints one line saying why not. Any findings that the
final review loop left open are listed in the PR body. See
[Pull requests](pull-requests.md) for the details.

## Run it

To run the flow, pass the file and a prompt to `orca run`:

```bash
orca run implement.sc "Add a rate limiter to the /login endpoint"
```

or, without installing Orca:

```bash
scala-cli run --workspace "$(mktemp -d)" implement.sc -- "Add a rate limiter to the /login endpoint"
```

The run creates a branch named from the prompt and commits after each stage.
On success, it switches you back to the branch you started on, while the work
stays on the run's branch and PR. If you interrupt the run and then run the
same command again, it resumes from the last committed stage.
[Branches, resume and worktrees](../using/run-lifecycle.md) covers the details.

```{note}
For editing flows with code completion, the
[Metals](https://scalameta.org/metals/) VS Code extension works well.
```

## The smallest flow

A flow does not need planning or review. For example, this one hands the
prompt to [Pi](../api/backends.md) and commits whatever it did:

```scala
flow(OrcaArgs(args)):
  stage("Run"):
    val session = pi.session("run", seed = userPrompt)
    session.run(userPrompt)
```

## Let an agent write it

You do not have to write a flow by hand. `orca create "<goal>"` has your
configured agents write a flow for you, and `orca fork <flow> "<changes>"` does
the same, starting from an existing flow. See [Orca Shell](../using/shell.md).

## Where to go next

- [Stages](stages.md) describes the rules that keep a flow resumable.
- [Choosing agents](choosing-agents.md) covers roles, tiers and tool limits.
- The other [built-in flows](../using/built-in-flows.md) are worked examples of
  issue handling, triage and review-only flows.
