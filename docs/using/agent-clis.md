# Agent CLIs

Orca drives the coding-agent CLIs you already have, the
[harnesses](../glossary/users.md#agents-and-conversations): `claude`, `codex`,
`opencode`, `pi` and `gemini`. Each one manages its own authentication; Orca
stores no secrets. Before running a flow, log in to the harness you use, and to
`gh` if the flow opens PRs or reads issues, each per its own instructions.

## Run in a sandbox

```{warning}
Run Orca in a sandbox. By default the coding agent edits files and runs shell
commands without asking.
```

You can narrow an agent's tools or auto-approval in the flow, see
[Choosing agents](../authoring/choosing-agents.md). For an unattended run the
practical boundary is a VPS or a local sandbox such as
[Sandcat](https://github.com/VirtusLab/sandcat) or [Docker
Sandboxes](https://docs.docker.com/ai/sandboxes/).

## Your instruction files apply

Orca's agents are ordinary harness sessions started in your repository. They
load the same instruction files (`~/.claude/CLAUDE.md`, `CLAUDE.md`,
`CLAUDE.local.md`, `AGENTS.md`, `GEMINI.md`, …), MCP servers, plugins and hooks
as your own sessions.

No one is present to approve tool calls, so:

- Coding turns auto-approve every tool by default.
- On claude, read-only roles (the planner, reviewers, and the agent that picks
  reviewers) can use only the tools Orca allows. Your MCP tools are blocked
  unless your claude settings `permissions.allow` them.
- On claude, opencode and pi, cheap one-shots (branch names, default commit
  messages) run with no tools and no MCP servers.

Check your instructions for:

- **Mandatory tool calls.** "Always call X first" needs X allowed, or write
  "if available".
- **A human in the loop.** In autonomous flows, "ask me before X" or "wait for
  confirmation" cannot work.

## OpenCode with a local Ollama model

- **Launcher, zero config.** In the flow script, pass a launcher:
  `flow(OrcaArgs(args), opencode = Some(w => OpencodeAgents.default(w, OpencodeLauncher.ollama("qwen3-coder"))))`.
  Orca starts the server via `ollama launch opencode`, which injects Ollama's
  provider config and pins that one model. Use bare `opencode`, no `withModel`.
  Needs the `ollama` CLI with the model already pulled. See
  [Extending flows in code](../authoring/extending.md).
- **Manual config.** Declare an `ollama` provider in
  `~/.config/opencode/opencode.json` (baseURL `http://localhost:11434/v1`, your
  models, `num_ctx` raised for tool use), then
  `opencode.withModel("ollama", "qwen3-coder")` (see
  [Backends](../api/backends.md)). This way you can declare several models and
  switch between them per turn.
