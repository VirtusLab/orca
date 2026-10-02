# Agent CLIs

Orca drives the coding-agent CLIs you already have, which it calls
[harnesses](../glossary/users.md#agents-and-conversations): `claude`, `codex`,
`opencode`, `pi` and `gemini`. Each harness manages its own authentication,
and Orca stores no secrets. Before you run a flow, log in to the harness you
use, following its own instructions, and to `gh` if the flow opens PRs or reads
issues.

## Run in a sandbox

```{warning}
Run Orca in a sandbox. Flows run unattended by default: the coding agent has
the full tool set (`ToolSet.Full`) and every tool call is auto-approved
(`AutoApprove.All`), so it edits files and runs shell commands without asking.
```

You can narrow an agent's tools or auto-approval in the flow itself, see
[Choosing agents](../authoring/choosing-agents.md). For an unattended run,
however, the practical boundary is a VPS or a local sandbox such as
[Sandcat](https://github.com/VirtusLab/sandcat) or
[Docker Sandboxes](https://docs.docker.com/ai/sandboxes/).

## Your instruction files apply

Orca's agents are ordinary harness sessions started in your repository, so
they load the same instruction files (`~/.claude/CLAUDE.md`, `CLAUDE.md`,
`CLAUDE.local.md`, `AGENTS.md`, `GEMINI.md`, …), MCP servers, plugins and
hooks as your own sessions do.

The difference is that no one is present to approve tool calls. This has a few
consequences:

- Coding turns auto-approve every tool by default.
- On claude, the read-only roles (the planner, the reviewers, and the agent
  that picks reviewers) can use only the tools Orca allows. In particular,
  your MCP tools are blocked unless your claude settings `permissions.allow`
  them.
- On claude, opencode and pi, cheap one-shots such as branch names and default
  commit messages run with no tools and no MCP servers.

Because of this, it is worth checking your instruction files for two things:

- **Mandatory tool calls.** An instruction like "always call X first" only
  works if X is allowed; otherwise, write "if available".
- **A human in the loop.** In an autonomous flow, "ask me before X" or "wait
  for confirmation" cannot work, as there is nobody to answer.

### Allowing MCP tools for claude's read-only roles

The read-only roles see your MCP servers, but a call is denied unless a
`permissions.allow` rule names it. The run's summary lists such calls as
`denied tool calls: mcp__<server>__<tool>`. Allow a whole server or single
tools in `~/.claude/settings.json` (all your repositories) or a committed
`.claude/settings.json` (this repository):

```json
{
  "permissions": {
    "allow": ["mcp__docs", "mcp__tracker__get_issue"]
  }
}
```

Allow only read-only tools: anything allowed here runs unprompted in every
claude session, not only in Orca's. If you previously answered "don't ask
again" in a session, that rule went to `.claude/settings.local.json`, which
is not committed.

## OpenCode with a local Ollama model

There are two ways to point OpenCode at a model served by Ollama.

### Launcher, zero config

Pass a launcher in the flow script:

```scala
flow(OrcaArgs(args), opencode = Some(w => OpencodeAgents.default(w, OpencodeLauncher.ollama("qwen3-coder"))))
```

Orca then starts the server via `ollama launch opencode`, which injects
Ollama's provider config and pins that one model. Use bare `opencode` in this
case, without `withModel`. This needs the `ollama` CLI with the model already
pulled. See [Extending flows in code](../authoring/extending.md).

### Manual config

Declare an `ollama` provider in `~/.config/opencode/opencode.json` (with
baseURL `http://localhost:11434/v1`, your models, and `num_ctx` raised for tool
use), then select a model with `opencode.withModel("ollama", "qwen3-coder")`
(see [Backends](../api/backends.md)). This way you can declare several models
and switch between them per turn.
