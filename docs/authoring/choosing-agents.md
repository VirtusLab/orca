# Choosing agents

A call takes an agent. Name it one of two ways.

**The role agents: `planningAgent`, `codingAgent`, `reviewAgent`.** Harness
agnostic. Each is resolved from [settings](../using/settings.md), defaulting to
claude. Use `planningAgent` for `Plan.*` calls, `codingAgent` for the
implementer's session, and `reviewAgent` for `allReviewers(...)`; the review
helpers default to it. Change settings and the whole flow follows. The shipped
flows use only role agents.

`codingAgent` is also the run's primary: its cheap tier names the branch,
discovers the stack settings and writes default commit messages.

**A specific harness and model: `claude.opus`, `codex.mini`,
`gemini.flash`.** Use a concrete accessor when a step needs a particular harness
or tier regardless of settings, say `claude.opus` for a step that must have the
strongest model. `codingAgent.opus` does not compile: model accessors exist
only on concrete harnesses. If you need a model, name the harness. Pin any
other model with `withModel(Model("…"))`. The models and accessors of each
harness are listed in [Backends](../api/backends.md).

## The cheap tier

`agent.cheap` is the harness's cheaper model:

- claude: haiku
- codex: mini
- gemini: flash
- opencode: luna when the provider is openai, else haiku
- pi: no cheaper model; `pi.cheap` is `pi`

Use it for one-shot summaries and pickers.

## Tool sets

`ToolSet` decides which tools exist at all:

```scala
// ReadOnly: reads only, no shell, no edits. Reviewers, plan review, briefs.
val reviewer = claude.withReadOnly

// NetworkOnly: reads plus read-only network. Planners that must read an issue or PR.
val planner = claude.withNetworkOnly

// Full (the default): write-capable.
```

`NoTools` exists too; the runtime uses it for cheap one-shots.

What `NetworkOnly` grants differs per harness:

- claude: `WebFetch` and `WebSearch`, replaceable with `claude.withNetworkTools(...)`,
  plus Orca's own GitHub issue and PR read tool.
- codex: network access inside the `workspace-write` sandbox. Codex has no
  read-only-with-network sandbox.
- gemini: `web_fetch`.
- opencode: `webfetch` is left to the server's default.
- pi: `bash`. Pi has no web tool, and `bash` also writes.

## Auto-approval

`AutoApprove` decides which of the available tools run without a y/n prompt.
It matters only for interactive turns, and only with the `Full` tool set:

```scala
val limited =
  claude.withAutoApprove(AutoApprove.Only(Set("Read", "Edit", "Grep")))
```

`AutoApprove.Only` fits interactive flows, where a human answers anything
outside the set. In an autonomous turn nobody can answer, so a call outside the
set hangs. Only claude enforces the set per tool; codex and gemini cannot
restrict per tool, so `Only` becomes full auto-approval there. For an
unattended run the practical boundary is a sandbox, see
[Agent CLIs](../using/agent-clis.md).

## How strongly each harness enforces a limit

A `ToolSet` requests a restriction. Each harness enforces it differently. When
a harness cannot enforce the requested limit, the turn's output is marked with
`!`.

| `ToolSet`, `AutoApprove` | Claude Code | Codex | OpenCode | Pi | Gemini |
|---|---|---|---|---|---|
| ReadOnly, * | Hard | Hard | Hard | Hard | PromptOnly |
| NetworkOnly, * | Hard | PromptOnly | Hard | PromptOnly | PromptOnly |
| Full, All | Hard | Hard | Ignored | Ignored | Hard |
| Full, Only(_) | Hard | SandboxApprox | Ignored | Ignored | Ignored |
| NoTools, * | Hard | PromptOnly | Hard | Hard | PromptOnly |

Hard: the CLI blocks the tools. PromptOnly: the agent is told, nothing blocks
it. SandboxApprox: a sandbox approximates the set. Ignored: the harness cannot
apply the restriction; auto-approval is always on there. A codex turn that
continues a conversation with `Only` is Ignored rather than SandboxApprox.

## Tuning an agent

Every harness shares these builders: `withModel`, `withCheapModel`,
`withAutoApprove`, `withSystemPrompt`, `withName` (its own line in the cost
log), `withReadOnly`, `withNetworkOnly`, `withSelfManagedGit`. Each returns a
new agent on the same harness. Agents on the same harness can continue each
other's conversations, see `chat.withAgent` in
[Talking to agents](talking-to-agents.md).

`withSelfManagedGit` opts one agent out of the rule that the runtime owns git:
by default every write-capable turn is told not to commit, push or switch
branches.

To replace an agent for the whole flow, or supply your own tool
implementations, see [Extending flows in code](extending.md).
