# Writing your first flow

This page builds the shipped `implement.sc` piece by piece. It plans a prompt
into tasks, implements and reviews each one, reviews the whole change, and
opens a PR.

## The header

Save the file as `implement.sc`. Every flow starts the same way:

```scala
//> using scala 3.9.0
//> using dep "org.virtuslab::orca:0.1.10"
//> using jvm 21

import orca.{*, given}
```

If the first line is a `//` comment, `orca list` shows it as the flow's
description.

## The body

`flow(OrcaArgs(args))` parses the command line and runs the body. Inside,
`userPrompt` is the prompt and `planningAgent`, `codingAgent` and `reviewAgent`
are the role agents from [settings](../using/settings.md).

```scala
flow(OrcaArgs(args)):
  val plan = stage("Plan"):
    Plan.autonomous.from(userPrompt, planningAgent).value
```

`stage` is the committing, resumable unit of work. The planner produces the
plan in one turn. The result is recorded in the progress log, so a re-run with
the same prompt skips this stage and reads the stored plan back. `.value` drops
the planning chat and keeps the `Plan`; see [Planning](planning.md).

## One stage per task

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

As before, a re-run skips completed tasks and picks up at the first incomplete
one.

`codingAgent.session("implementer", seed = plan.brief)` is a durable
conversation. A session is keyed by its name plus the stage it is created in,
so this loop creates one session per task. On first use the session is primed
with the plan's brief, and if the harness loses the conversation, it is started
again from that seed. See [Talking to agents](talking-to-agents.md).

`reviewThenFix` runs one review round and one fix turn. It returns the findings
it left open; the final review below is told about them. See
[Review and fix loops](review.md) for how reviewers are picked and how format
and lint run.

## The final review

Each task ran one review round, so nobody checked the fixes themselves. The
final review loops over the whole change until the reviewers are satisfied:

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
stages. It is seeded the same way. `Title` wraps a task title.
`ReviewDiff.WholeRun` shows reviewers everything since the run started.
`maxFixTurns = 5` is above the default of 3, because nothing reviews the code
after this loop. `priorOpenFindings` hands over what the task reviews left
open.

## Open a PR

```scala
  openPrIfGitHub(
    summarisingAgent = codingAgent.cheap,
    openFindings = openFindings
  )
```

It opens a PR when the repository is on GitHub and `gh` can reach it. Otherwise
it prints one line saying why not. Findings the loop left open are listed in
the PR body. See [Pull requests](pull-requests.md).

## Run it

```bash
orca run implement.sc "Add a rate limiter to the /login endpoint"
```

or, without installing Orca:

```bash
scala-cli run --workspace "$(mktemp -d)" implement.sc -- "Add a rate limiter to the /login endpoint"
```

The run creates a branch named from the prompt and commits each stage. On
success it switches you back to the branch you started on. The work stays on
the run's branch and PR. Interrupt it, and run the same command again: it
resumes from the last committed stage.
[Branches, resume and worktrees](../using/run-lifecycle.md) covers the details.

For editing flows with code completion, the
[Metals](https://scalameta.org/metals/) VS Code extension works well.

## The smallest flow

A flow does not need planning or review. This one hands the prompt to
[Pi](../api/backends.md) and commits whatever it did:

```scala
flow(OrcaArgs(args)):
  stage("Run"):
    val session = pi.session("run", seed = userPrompt)
    session.run(userPrompt)
```

## Let an agent write it

`orca create "<goal>"` has your configured agents write a flow for you.
`orca fork <flow> "<changes>"` does the same, starting from an existing flow.
See [Orca Shell](../using/shell.md).

## Where to go next

- [Stages](stages.md): the rules that keep a flow resumable.
- [Choosing agents](choosing-agents.md): roles, tiers and tool limits.
- The other [built-in flows](../using/built-in-flows.md) are worked examples of
  issue handling, triage and review-only flows.
