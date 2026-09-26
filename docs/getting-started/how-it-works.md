# How Orca works

## A flow is a script

A flow is a Scala script whose body is `flow(OrcaArgs(args)): ...`, where
`OrcaArgs` parses the prompt and flags from the script's arguments. Inside it
you call agents, read the repository, and group work into **stages**. Orca runs
it with scala-cli. The script is ordinary code, so loops, conditions and helper
functions decide what happens, not an agent's judgement.

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

A `stage(name)` body is the unit of work. When it finishes, Orca commits
everything it changed together with an entry in the **progress log**, a file
under `.orca/runs/` committed on the feature branch. One stage, one commit.

Because the log is committed with the code, the two cannot drift apart. That
is what makes a run **resumable**: run the same prompt again and each stage
already in the log is skipped and its recorded result reused. Work continues
from the first unfinished stage. See
[Branches, resume and worktrees](../using/run-lifecycle.md).

## Orca owns git

Orca creates the feature branch and, at the end, removes the progress log
(there is nothing left to resume) and opens the PR. Agents are told not to
commit, push or switch branches. They edit files; the flow decides what
happens to the edits. The compiler helps: a call that writes to the repository
or runs an agent does not compile outside a stage. See
[Stages](../authoring/stages.md).

## Agents are yours

Orca drives the coding-agent CLIs you already use, the
[harnesses](../glossary/users.md#agents-and-conversations): `claude`, `codex`,
`opencode`, `pi`, `gemini`. Which one handles the planning, coding and review
roles comes from [settings](../using/settings.md), so a flow never needs to
name a harness. Agents run in your repository with your instruction files, MCP
servers and hooks, see [Agent CLIs](../using/agent-clis.md).

A flow talks to an agent in three ways: a one-shot question, a conversation
that ends when the script exits, or a **session** that survives a crash and a
resume. See [Talking to agents](../authoring/talking-to-agents.md).

## Review is code

The shipped flows review every task with a set of reviewer agents, each with
its own prompt, and hand the findings back to the coder to fix. A final loop
reviews the whole change. Reviewers, lint and format commands are configured
per project. Findings still open when the review ends are listed in the PR
body. See [Review and fix loops](../authoring/review.md).
