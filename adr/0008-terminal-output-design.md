# 0008. Terminal output: event log + status bar

Status: Accepted · Date: 2026-04-27

## Context

Flow runs produce three kinds of textual signal:

  - **Stage transitions** — `▶ implement: …` → work happens →
    (implicit completion when the next event arrives).
  - **Tool activity** — agent-side events (`AssistantToolCall`,
    `ToolResult`, `Step` from `git.createBranch`, etc.).
  - **Liveness** — "the agent is still thinking; orca hasn't hung."

Earlier iterations rendered all three inline in a single stream: the
spinner drew a 2-line orca-and-wave above its label, every stage
emitted `▶ name` and `✔ name`, and tool subprocess output was
whatever os-lib happened to do by default. The result was crowded,
visually inconsistent, and prone to artifacts: a long stage label
would wrap the terminal, the spinner's `\r[2K` would only clear
the wrapped tail, and successive frames would march down the screen.
Worse, subprocesses inheriting stderr (os-lib 0.11.x's default)
would write *between* spinner frames, producing visible tearing.

This ADR records the terminal-output design that replaces all of
that.

## Decision

**Two zones, one stream.** The terminal output is logically split
into an *event log* that grows top-to-bottom and a *status line*
pinned to the bottom. Both are written to the same `PrintStream`
(typically stderr). Coordination lives in a single component,
`StatusBar`, which owns the cursor-control discipline: every
event-log write goes through `appendLog`, which clears the status
row, prints the log line, and re-renders the status below.

**Stage completions are silent in the log.** Starting the next event
implicitly tells the user the previous one finished. Without this,
the cumulative `✔ plan` / `✔ implement: …` / `✔ Review & fix` lines
dominated the visual flow. The depth-stack-aware status bar is the
ongoing signal: it shows the deepest active stage, and pops back to
the parent on completion. Top-level stages aren't special — no `✔`
ever appears in the log.

**Indentation tracks stage nesting.** `StageDepth` counts push/pop;
every line in the event log indents by `2 × depth` spaces. The stage
marker itself prints at the parent's content indent so opening lines
align with their parent's content. `Step` events (single-line
notes — `git.createBranch`'s "Switched to branch X", `fixLoop`'s
"Discarded N issues") render at the current content indent.

**JSON-only assistant payloads are suppressed.** When the flow asked
for a structured `O`, the agent's final turn is JSON. Streaming it
verbatim flashes raw `{"tasks":[...]}` on the way to the parsed
`Plan`. The renderer buffers `AssistantTextDelta`s through
`AssistantTurnEnd` and drops the buffer if it parses as a JSON value
(cheap first/last-char heuristic). The flow script renders the
parsed plan in human-readable form via its own `println`s.

**Tool-call paths under `workDir` show as relative.** Absolute paths
outside `workDir` stay absolute, so out-of-project file access is
visually obvious. Free-form `command` / `pattern` / `query`
fields pass through verbatim — only `file_path`/`path` headline
fields get the rewrite.

**Subprocess stderr must be captured.** os-lib 0.11.x defaults
`os.proc(...).call(...)`'s `stderr` to `Inherit`. A tool calling
`os.proc` directly lets the child write its stderr to the parent
terminal, bypassing the StatusBar entirely — the artifact that
prompted this redesign. The fix is encoded as `subprocess.QuietProc`:
a one-method helper that captures both pipes. Tool subprocesses go
through QuietProc or a `CliRunner` (which itself uses explicit
piping). Direct `os.proc` in production tool code is a leak.

## Glyph and colour mapping

Used in the event log:

| Glyph | Colour | Meaning |
| ----- | ------ | ------- |
| `▶` | cyan | Stage start, or a `Step` (single-line note: branch switch, "discarded N issues"). No closing glyph for either. |
| `▸` | cyan, bold | The user's prompt at the start of an interactive session. |
| `●` | magenta, bold | An assistant prose message. JSON-only payloads are suppressed; the flow script renders the parsed structured output. |
| `·` | grey | Assistant "thinking" prose. Hidden by default; `showThinking = true` reveals it. |
| `⏺` | blue, bold | A tool call the agent is making. The headline argument follows in grey. |
| `⎿` | grey | The result of the preceding tool call, truncated to one line. |
| `✖` | red | An error — either an `OrcaEvent.Error` from a stage that threw, or a non-fatal mid-session error. |
| `?` | yellow | Approval request — the agent wants a tool that isn't auto-approved. |

Used in the status line:

| Glyph cycle | Meaning |
| ----------- | ------- |
| `⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏` | Spinner — agent is working. The label is the current (innermost) stage name, truncated to fit one terminal row. |

Glyph choices are pragmatic:

- `▶` doubles for stages and Steps deliberately. Both are "something
  happened in the log; carry on." The renderer doesn't differentiate
  beyond colour.
- `▸` (vs `▶`) for the user marks the human's turn distinctly without
  introducing a third arrow shape.
- `⏺` / `⎿` for tool call / result echo the Claude Code aesthetic;
  changing them is fine but they should stay paired.
- The braille spinner's column-stable width (each frame is one
  glyph) is what makes single-line redraws clean.

## Auto-detect

Colours and animation auto-disable when the renderer suspects its
output is being captured:

- No controlling console (`System.console() == null`).
- `NO_COLOR` set (the convention).
- `CI` env var non-empty.
- `ORCA_NO_ANIMATION` set (animation only — colour stays on).

In the auto-disabled mode, `StatusBar` writes log lines plain (no
ANSI escapes) and the spinner is a no-op. Event-log content is
identical, so captured CI logs read the same as a live terminal.

## Consequences

- The renderer is the single legitimate writer to the parent's
  terminal. Every other code path that wants to surface
  user-facing text emits an `OrcaEvent` or `ConversationEvent`.
- `os.proc(...).call(...)` is a code smell in tool code. CI could
  enforce this with a grep, but currently it's a convention
  documented here and in the README.
- Long stage names (the agent dumping the full prompt into
  `Task.summary`) no longer break rendering — the status bar
  truncates to one row. The event log still shows the full text on
  one line; flow authors should keep `summary` short (~60 chars) for
  readability.
- Renaming or recolouring a glyph is a breaking change for users who
  have built mental models around the existing mapping. Treat the
  table above as a contract.

## Alternatives considered

- **JLine `Status` widget.** JLine ships a dedicated status-line
  abstraction that handles cursor save/restore properly across
  terminal resize. Adopting it would replace `StatusBar` entirely.
  Rejected for now: pulls more of JLine's public API into the
  rendering layer (we already use it only for the readline prompter)
  and the rolling-our-own version handles the common cases. Worth
  revisiting if we hit limitations (window resize, scrolling regions).
- **Pin the status line via ANSI scrolling regions** (`[r;<n>r`).
  Avoids the "redraw on every log write" overhead but requires
  knowing the terminal height up front and degrades poorly when the
  terminal is resized mid-flow. The redraw approach is simpler and
  Good Enough.
- **Keep `✔` for top-level stages only.** Earlier iteration. Caused
  thrashing between consecutive top-level stages (each completion
  brought the bar down, the next start brought it back up) and
  doubled lines for the user without adding signal. Removing `✔`
  entirely is cleaner.

## Testing

- `StatusBarTest` covers inline mode, animated mode, the long-label
  truncation, status redraw across appendLog calls, and the
  on-stop clear escape.
- `TerminalInteractionTest` pins the indentation math and the
  no-`✔`-in-log invariant.
- `TerminalConversationRendererTest` covers JSON-only suppression,
  prose flushing at TurnEnd, and tool-call/result rendering.
- `QuietProcTest` pins the stderr-capture contract — the
  abstraction that makes the artifact this ADR closes impossible
  in tool code.
