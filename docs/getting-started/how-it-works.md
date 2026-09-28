# How Orca works

## A flow is a script

A flow is a Scala script whose body is `flow(OrcaArgs(args)): ...`, where
`OrcaArgs` parses the prompt and flags from the script's arguments. Inside the
flow you call agents, read the repository, and group the work into **stages**.
Orca runs the script with scala-cli. Since the script is ordinary code, loops,
conditions and helper functions decide what happens, rather than an agent's
judgement.

For example, a flow that plans the work, implements and reviews each task, and
finishes with a final review has this shape:

```{mermaid}
flowchart LR
    P[Prompt] --> S1[Stage: Plan]
    S1 --> S2[Stage: Task 1<br/>code + review]
    S2 --> S3[Stage: Task 2<br/>code + review]
    S3 --> S4[Stage: Final review]
    S4 --> PR[Push + open PR]
    S1 -. commit .-> G[(feature branch)]
    S2 -. commit .-> G
    S3 -. commit .-> G
    S4 -. commit .-> G
```

## Stages commit

The body of a `stage(name)` call is the unit of work. When it finishes, Orca
commits everything it changed, together with an entry in the **progress log**:
a file under `.orca/runs/` that is committed on the feature branch. In other
words, one stage produces one commit.

Because the log is committed together with the code, the two cannot drift
apart. This is what makes a run **resumable**: if you run the same prompt
again, each stage that is already in the log is skipped and its recorded result
is reused, so work continues from the first unfinished stage. See
[Branches, resume and worktrees](../using/run-lifecycle.md) for details.

## Orca owns git

Orca creates the feature branch and, at the end of the run, removes the
progress log (there is nothing left to resume) and opens the PR. The agents are
told not to commit, push or switch branches: they only edit files, and the flow
decides what happens to the edits.

Note that a flow can only push, write files, post to GitHub or run an agent
from inside a stage. Such a call anywhere else is a compile error, which
guarantees that every side effect is checkpointed by the stage's commit. See
[Stages](../authoring/stages.md).

## Agents are yours

Orca drives the coding-agent CLIs you already use, called
[harnesses](../glossary/users.md#agents-and-conversations): `claude`, `codex`,
`opencode`, `pi` and `gemini`. Which one handles each of the planning, coding
and review roles comes from [settings](../using/settings.md), so a flow never
needs to name a harness. The agents run in your repository with your
instruction files, MCP servers and hooks; see
[Agent CLIs](../using/agent-clis.md).

A flow can talk to an agent in three ways: as a one-shot question, as a
conversation that ends when the script exits, or as a **session**, which
survives a crash and a resume. See
[Talking to agents](../authoring/talking-to-agents.md).

## Review is code

The shipped flows review every task with a set of reviewer agents, each with
its own prompt, and hand the findings back to the coder to fix. A final loop
then reviews the whole change. The reviewers, as well as the lint and format
commands, are configured per project. Any findings that are still open when
the review ends are listed in the PR body. See
[Review and fix loops](../authoring/review.md).
