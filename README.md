# Orca

Deterministic, AI-driven development flows.

Orca allows you to programmatically define software development workflows where
AI agents perform the coding. If you want AI-generated code to always be
reviewed by another agent, don't try to coerce the agents; just express that
requirement in code. Don't waste tokens on formatting, committing, or creating
PRs - all of this can be handled by an ordinary script.

Orca comes with an `orca` cli, which can be used interactively by humans, or
headlessly by humans and agents alike. A number of built-in flows, implementing
e.g. a plan-implement-review loop, allow you to start using Orca right away.

Orca flow scripts are written in Scala, and can be run with a single command
through [scala-cli](https://scala-cli.virtuslab.org), which is installed by the
`orca` installer. No other dependencies are needed - everything is automatically
bootstrapped. Scala 3 looks like Python, but with types - so you get quick
feedback if your flow script has any problems.

Orca's development flows are resumable, so that if work is interrupted mid-flow
for any reason, it can be continued from the last commit.

You can use Orca to orchestrate development in any language and ecosystem.

Orca assumes that it has configured, logged-in access to Claude, Codex,
OpenCode, Pi or Gemini (depending which backend you use), as well as `gh` and
`git`.

**Documentation: [orca.virtuslab.com](https://orca.virtuslab.com)**

## Install

One command installs `scala-cli` (via its official installer) if you don't have
it already, and writes the `orca` executable to `~/.local/bin/orca`:

```bash
curl -fsSL https://raw.githubusercontent.com/VirtusLab/orca/master/install.sh | bash
```

## Start using

Run `orca` in your repository. The first run asks which agent and model to use
for planning, coding and review. Then pick a flow (`implement.sc` comes first)
and enter your prompt.

The same, without the menu, for example from a coding agent or CI:

```bash
orca run implement.sc "add a rate limiter to /login"
```

Orca creates a feature branch, plans the change into tasks, implements and
reviews each one, reviews the whole change, and opens a PR when the repository
is on GitHub. Each stage is committed as it finishes; if the run is interrupted,
run the same command again and it continues from the last commit.

A flow is a scala-cli script, so it also runs with no install:

```bash
scala-cli run --workspace "$(mktemp -d)" implement.sc -- "add a rate limiter to /login"
```

> [!WARNING] **Orca is designed to work in a sandboxed environment!** Coding
> agent tool usage is auto-approved by default: write-capable turns let the
> agent edit files and run shell commands without prompting. Use a VPS or a
> local sandbox such as [Sandcat](https://github.com/VirtusLab/sandcat) or
> [Docker Sandboxes](https://docs.docker.com/ai/sandboxes/), or narrow the
> agents' tools in the flow.

## A flow

Flows are ordinary Scala scripts. This one plans, implements each task with a
review, and opens a PR:

```scala
//> using scala 3.9.0
//> using dep "org.virtuslab::orca:0.1.10"
//> using jvm 21

import orca.{*, given}

flow(OrcaArgs(args)):
  val plan = stage("Plan"):
    Plan.autonomous.from(userPrompt, planningAgent).value

  for task <- plan.tasks do
    stage(s"Task: ${task.title}"):
      val session = codingAgent.session("implementer", seed = plan.brief)
      session.run(task.description)
      reviewThenFix(coderSession = session, reviewers = allReviewers(reviewAgent), task = task)

  val openFindings = stage("Final review"):
    val finalFixer = codingAgent.session("final-fixer", seed = plan.brief)
    reviewAndFixLoop(
      coderSession = finalFixer,
      reviewers = allReviewers(reviewAgent),
      task = Task(Title("The whole planned change"), plan.brief),
      diff = ReviewDiff.WholeRun
    )

  openPrIfGitHub(summarisingAgent = codingAgent.cheap, openFindings = openFindings)
```

Each `stage` commits on completion and is skipped on resume. The tutorial at
[orca.virtuslab.com](https://orca.virtuslab.com) explains every line; the
shipped flows live in [`flows/`](flows/).

## Documentation

- [orca.virtuslab.com](https://orca.virtuslab.com) — user documentation: usage,
  built-in flows, settings, authoring flows, API reference, glossary. Source in
  [`docs/`](docs/).
- [`adr/`](adr/) — architecture decision records.
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — building, testing, and running a
  locally modified orca.
- [`AGENTS.md`](AGENTS.md) — internals, architecture, and coding conventions;
  the same file AI assistants pick up.

## License

Apache 2.0 — see [LICENSE](LICENSE).

## Copyright

Copyright (C) 2026 VirtusLab [https://virtuslab.com](https://virtuslab.com).
