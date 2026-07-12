# 0009. Announce typeclass for friendly summaries of structured outputs

Status: Accepted · Date: 2026-04-29

## Context

Flow scripts call `claude.resultAs[Plan].interactive(...)` (or any
other variant) to get a parsed `Plan` back. The agent's final turn
on the wire is the JSON payload — `{"tasks":[...]}` — that the
library deserializes into the case class. Two questions then collide:

1. **Should the user see the raw JSON?** Streaming a structured
   payload verbatim flashes a wall of curly braces between "the
   agent is working" and "we have a plan". It's noise.
2. **Should the user see a friendly summary?** "Planned 3 tasks on
   branch 'feat-foo'" + bullet list is the actual signal — what
   you'd want to print in a Slack channel or read in a CI log.

The first attempt addressed (1) only: `TerminalConversationRenderer`
buffered each turn's deltas, and at `AssistantTurnEnd` ran a cheap
first/last-char heuristic — if the buffer started with `{`/`[` and
ended with the matching close, drop it. Flow scripts that wanted (2)
called `plan.announce` (a method on `Plan`) which emitted an
`OrcaEvent.Step` carrying the summary.

That worked, but two things were wrong:

- **The renderer was hiding output.** The heuristic was lossy: a
  malformed JSON-ish payload would be silently swallowed, and a
  user reading the transcript couldn't tell whether the agent
  produced a structured payload or fell back to prose. Hidden
  output is the kind of thing that looks fine until you're debugging
  a misbehaving agent at 2am.
- **Friendly summaries were opt-in *and* manual.** Every flow had to
  remember to call `plan.announce`. Forget it and the user sees
  nothing meaningful — the JSON is suppressed, the summary is
  unprinted. Two failure modes for one feature.

## Decision

Introduce an `Announce[O]` typeclass and let the library auto-emit
the agent's parsed result via a single `OrcaEvent.StructuredResult`
event that carries both the raw payload and the optional summary —
so the consumer (terminal listener, Slack adapter, structured log)
chooses the rendering instead of the renderer guessing.

```scala
trait Announce[O]:
  def message(value: O): String

object Announce:
  given default[O]: Announce[O] = _ => ""
  def from[O](f: O => String): Announce[O] = ...

enum OrcaEvent:
  ...
  case StructuredResult(raw: String, summary: Option[String])
```

- **Catch-all default given returns `""`.** Every type has an
  `Announce[O]` resolvable — the empty string is the contract for
  "no announcement to make". `DefaultAgentCall` normalises the
  empty-string sentinel to `None` so listeners can pattern-match
  on `summary` cleanly.
- **Specific instances win via Scala 3's specificity rules.**
  `given Announce[Plan] = Announce.from(...)` in `Plan`'s companion
  is more specific than the catch-all and resolves preferentially.
- **`AgentCall.resultAs[O]` adds the bound:**
  `def resultAs[O: JsonData : Announce]`. `DefaultAgentCall` consumes
  the instance via a `using` parameter and emits a single
  `StructuredResult(raw, summary)` event after parsing — on every
  invocation variant (`autonomous`, `startSession`, `continueSession`,
  `interactive`, `continueInteractive`).
- **Manual trigger lives in the flow module.** `value.announce` is
  an extension method that requires `FlowContext`, so it lives in
  `flow/src/main/scala/orca/announceExtension.scala`. The typeclass
  itself ships from `tools/` and has no `FlowContext` dependency,
  which keeps the module-dependency direction (`flow → tools`) intact.
- **Conversation suppresses the JSON during structured-mode
  streaming.** `Conversation.outputSchema: Option[String]` exposes
  whether the call was launched in structured-output mode. The
  terminal renderer reads it and drops the assistant-text buffer at
  `AssistantTurnEnd` in that case — so the JSON doesn't stream char
  by char as `●` and then get duplicated by a separate friendly
  summary. The single `StructuredResult` event is the canonical
  surface; the listener decides what to render.

Net effect for a `Plan` flow: the user sees the agent's tool calls
(`⏺ Glob`, `⏺ Read`, …), then a single `▶ Planned N tasks on branch
'X'` line — no raw JSON in between. If a domain type doesn't define
an `Announce` instance, the listener falls back to rendering the
`raw` field under the `●` glyph so the result remains visible.

## Why typeclass over trait

Two shapes were considered for the friendly-summary contract:

- `trait Announceable { def announce: String }` — types that want
  a summary mix it in.
- `trait Announce[O] { def message(value: O): String }` — a
  typeclass instance is provided alongside the type.

The typeclass won on three counts:

1. **Retroactive instances.** Adding `Announce[Foo]` for a `Foo`
   the user doesn't own (e.g. a third-party result type) is a
   one-liner with the typeclass and impossible with the trait.
2. **Idiomatic alignment.** `JsonData[O]` is already a typeclass
   bound on `resultAs`. Adding a sibling trait would introduce a
   second mechanism for the same surface; another typeclass costs
   nothing extra to learn.
3. **Default no-op without inheritance gymnastics.** A catch-all
   `given default[O]` makes every type announceable-by-default
   (with an empty message). The trait version would either force
   every output type to extend `Announceable` (pollution) or
   require a base class with a default method (Scala 3 doesn't
   give us that for arbitrary case classes).

## Consequences

- **No flow boilerplate for the common case.** A case class with
  `given Announce[Foo]` in its companion gets auto-summarised on
  every `resultAs[Foo]` call. Flow scripts don't import or call
  anything extra.
- **Renderer is dumber.** ~30 lines of suppression heuristic
  removed; the renderer now flushes whatever the agent emitted.
- **Test stubs propagate the bound.** Every hand-rolled `Agent`
  or `AgentCall` in tests grew `: Announce` on its `resultAs[O]`
  override. Mechanical churn, but a real consequence of widening
  the structural surface.
- **Module split.** The typeclass + default + `from` helper sit in
  `tools/`; the `value.announce` extension sits in `flow/`. A
  callsite that only needs the bound (e.g. `DefaultAgentCall`)
  imports from `tools/` and doesn't pull in `FlowContext`.
- **The empty-string contract is load-bearing.** `Announce.from(_ => "")`
  and an absent specific given are observationally identical. A
  caller can return an empty message to mean "skip the announce
  this time" without the library treating that as an error.

## Alternatives considered

- **Keep the suppression heuristic, add an explicit `announce` call.**
  The status quo. Rejected because hidden agent output is harder
  to debug than visible-but-redundant output, and because making
  the friendly summary a separate manual step doubled the failure
  surface (forget the call, get nothing).
- **Auto-emit `toString` if no specific `Announce[O]` is provided.**
  Tempting (no boilerplate at all) but `toString` on a case class
  is `"Plan(List(Task(...)))"` — useful for debugging, awful as a
  user-facing summary. Better to make the fallback silent and let
  authors opt in deliberately.
- **Bake the summary into `JsonData`.** Conflates two concerns: how
  to encode/decode and how to summarise. Many types want one but
  not the other; coupling them means every `derives JsonData`
  has to also pick a summary, and a `derives JsonData` for a
  third-party type can't supply one at all.
- **Use the parsed value's `Show`/`Display` typeclass from a
  third-party library.** No such typeclass is in the stdlib, and
  pulling in cats just for this is disproportionate. The local
  typeclass costs ~30 lines and stays in the user's flat import.

## Amendment (2026-07-11): deliberate silence vs. no instance

The consequence above — "`Announce.from(_ => "")` and an absent
specific given are observationally identical" — is retired. When the
raw-payload fallback landed (ADR 0008: a result with no `Announce[O]`
renders the truncated raw JSON under `●` rather than disappearing),
identity between the two cases silently converted every deliberately
silent instance (`ReviewResult`, `IgnoredIssues`, `PrSummary` — types
whose outcome the call site narrates itself) into a raw-JSON line on
top of the call site's own narration.

`StructuredResult.summary` is now tri-state:

- `Some(text)` — a specific `Announce[O]` produced a summary; rendered
  as `▶`;
- `Some("")` — a specific `Announce[O]` deliberately says nothing;
  rendered as nothing (the trace log still records the raw payload);
- `None` — no specific `Announce[O]` exists (the catch-all
  `Announce.default` resolved); renderers fall back to the raw payload
  under `●` so an unannounced result stays visible.

Authoring is unchanged: `Announce.from(_ => "")` still means "say
nothing", and `Announce.from`'s empty-string normalisation plus
`fromOption`'s `None` both land on the deliberate-silence arm. The
distinction is drawn at the emission edge (`DefaultAgentCall`), which
recognises the catch-all instance (`Announce.NoSpecific`) rather than
inspecting the message.
