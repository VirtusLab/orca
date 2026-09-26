# Backends

A **backend** is a harness as a flow sees it: the five agents `claude`,
`codex`, `opencode`, `pi` and `gemini`. They expose the same calls, described
in [Talking to agents](../authoring/talking-to-agents.md):

- durable: `session(name, seed): FlowSession` → `.run(prompt)` /
  `.resultAs[O].run(input)`
- one-shot: `run(prompt)`, `resultAs[O].{autonomous,interactive}.run(input)`
- ephemeral multi-turn: `chat(): Chat` → `.run(prompt)` /
  `.resultAs[O].{autonomous,interactive}.run(input)`

`interactive` exists only on `resultAs[O]`. [`FlowSession` and
`Chat`](data-structures.md#conversations) are the handles. All five share the
builders `withModel`, `withCheapModel`, `withAutoApprove`, `withSystemPrompt`,
`withName`, `withReadOnly`, `withNetworkOnly`, `withSelfManagedGit`, see
[Choosing agents](../authoring/choosing-agents.md). `Model` wraps a model id
string.

The table lists what differs. "Bare" means the accessor with no model chosen;
`cheap` is the model `agent.cheap` picks.

| Agent | Model accessors | Notes |
|---|---|---|
| `claude` | `haiku`, `sonnet`, `opus`, `fable`; `cheap` → haiku; `withModel(Model)` | Claude Code. Bare `claude` is Opus with the 1M-token context window. Use `claude.sonnet` or `claude.haiku` for cheap one-shot calls, `claude.fable` for the hardest ones. `withNetworkTools(...)` replaces the tools the `NetworkOnly` [tool set](../authoring/choosing-agents.md#tool-sets) grants. |
| `codex` | `mini`; `cheap` → mini; `withModel(Model)` | OpenAI Codex. Bare `codex` pins GPT-6 Sol, which needs a codex CLI version that has this model. `codex.mini` is GPT-6 Luna. |
| `opencode` | `anthropicOpus`, `anthropicSonnet`, `anthropicHaiku`, `openaiAstra`, `openaiSol`, `openaiLuna`; `cheap` is provider-matched (openai → luna, else anthropicHaiku); `withModel(providerModel)` or `withModel(provider, modelId)` | [OpenCode](https://opencode.ai), driven over HTTP and SSE against a headless `opencode serve`, started lazily and shared for the [attempt](../glossary/users.md#flows-and-runs); sessions survive the server. Spans providers, so models are provider-qualified: `opencode.withModel("openai/gpt-5-mini")`, `opencode.withModel("ollama", "llama3.1")`. Inherits your configured providers and auth. |
| `pi` | `withModel(Model("provider/model"))` | [Pi](https://pi.dev/), driven through `pi --mode rpc`. Provider and model selection follow Pi's own configuration. Interactive calls can ask clarifying questions through Orca's `ask_user` bridge. |
| `gemini` | `flash`; `cheap` → flash; `withModel(Model)` | Google Gemini CLI, driven via `gemini --output-format stream-json`. Bare `gemini` pins Gemini 3.1 Pro (preview); `gemini.flash` is Gemini 3.8 Flash. Structured output is prompt-enforced, since Gemini has no schema flag; `withReadOnly` maps to `--approval-mode plan`. |

How strongly each backend enforces a `ToolSet` is tabulated in
[Choosing agents](../authoring/choosing-agents.md#how-strongly-each-harness-enforces-a-limit).

In `settings.properties` a backend is named by the same word, with an optional
model: `codingAgent = codex:gpt-5-mini`. See [Settings](../using/settings.md).
