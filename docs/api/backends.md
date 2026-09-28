# Backends

A **backend** is a harness as a flow sees it. There are five of them,
available in a flow body as the agents `claude`, `codex`, `opencode`, `pi` and
`gemini`. They all expose the same calls, described in
[Talking to agents](../authoring/talking-to-agents.md):

- durable: `session(name, seed): FlowSession`, then `.run(prompt)` or
  `.resultAs[O].run(input)` on the session
- one-shot: `run(prompt)`, or `resultAs[O].{autonomous,interactive}.run(input)`
- ephemeral multi-turn: `chat(): Chat`, then `.run(prompt)` or
  `.resultAs[O].{autonomous,interactive}.run(input)` on the chat

Note that `interactive` exists only on `resultAs[O]`. [`FlowSession` and
`Chat`](data-structures.md#conversations) are the handles you get back.

All five backends also share the builders `withModel`, `withCheapModel`,
`withAutoApprove`, `withSystemPrompt`, `withName`, `withReadOnly`,
`withNetworkOnly` and `withSelfManagedGit`; see
[Choosing agents](../authoring/choosing-agents.md) for what each one does.
`Model` wraps a model id string.

The sections below describe what differs between the backends: which model
accessors each one has, and how it is driven. A "bare" backend is the accessor
with no model chosen, for example plain `claude`; `cheap` is the model that
`agent.cheap` picks for that backend.

## `claude`

Claude Code. Bare `claude` is Opus with the 1M-token context window. The model
accessors are `haiku`, `sonnet`, `opus` and `fable`; `cheap` picks haiku, and
`withModel(Model)` sets any model id.

Use `claude.sonnet` or `claude.haiku` for cheap one-shot calls, and
`claude.fable` for the hardest ones.

`withNetworkTools(...)` replaces the tools the `NetworkOnly`
[tool set](../authoring/choosing-agents.md#tool-sets) grants.

## `codex`

OpenAI Codex. Bare `codex` pins GPT-6 Sol, which needs a codex CLI version that
has this model. The model accessor is `mini`, which is GPT-6 Luna; `cheap`
picks mini, and `withModel(Model)` sets any model id.

## `opencode`

[OpenCode](https://opencode.ai), driven over HTTP and SSE against a headless
`opencode serve`. The server is started lazily and shared for the
[attempt](../glossary/users.md#flows-and-runs); sessions survive the server.
OpenCode inherits your configured providers and auth.

OpenCode spans providers, so models are provider-qualified. The model accessors
are `anthropicOpus`, `anthropicSonnet`, `anthropicHaiku`, `openaiAstra`,
`openaiSol` and `openaiLuna`. `cheap` is provider-matched: openai maps to luna,
anything else to anthropicHaiku. To choose another model, use
`withModel(providerModel)` or `withModel(provider, modelId)`, for example:

- `opencode.withModel("openai/gpt-5-mini")`
- `opencode.withModel("ollama", "llama3.1")`

## `pi`

[Pi](https://pi.dev/), driven through `pi --mode rpc`. Provider and model
selection follow Pi's own configuration; to pick one, use
`withModel(Model("provider/model"))`. Interactive calls can ask clarifying
questions through Orca's `ask_user` bridge.

## `gemini`

Google Gemini CLI, driven via `gemini --output-format stream-json`. Bare
`gemini` pins Gemini 3.1 Pro (preview). The model accessor is `flash`, which is
Gemini 3.8 Flash; `cheap` picks flash, and `withModel(Model)` sets any model
id.

Structured output is prompt-enforced, since Gemini has no schema flag.
`withReadOnly` maps to `--approval-mode plan`.

## Tool set enforcement and settings

How strongly each backend enforces a `ToolSet` is tabulated in
[Choosing agents](../authoring/choosing-agents.md#how-strongly-each-harness-enforces-a-limit).

In `settings.properties` a backend is named by the same word, with an optional
model after a colon, for example `codingAgent = codex:gpt-5-mini`. See
[Settings](../using/settings.md).
