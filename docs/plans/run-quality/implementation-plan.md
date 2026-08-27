# Run quality — implementation plan

Expands [specification.md](specification.md). All paths repo-relative. Planned
against `master` @ `72360305`; every file/line reference was re-read there and
verified by an independent pass.

**How to read the "maintainer decision" markers.** Exactly one open decision
remains (below). A bullet marked with it is written as a default that the
implementer should build; if the decision is answered the other way, the
plan names the alternative and it is a small change. Nothing here is blocked.

## Landing order

**PR 1 first. PRs 2–5 are then independent of each other and can land in any
order.**

PR 1 rewrites `ReviewFormatting.formatUnresolvedFindings` and the exit wording
in `ReviewLoop`; nothing else in the plan touches those lines once the wording
work lives here rather than in the rendering PR. Two smaller overlaps remain
and imply a rebase, not an ordering: PRs 2 and 5 both edit
`ReviewerSelector.scala` (at `:168` and `:119`), and PRs 4 and 5 both edit
`runner/.../flow.scala`.

## Open decision

**Dedup rule for cross-reviewer duplicates (PR 2).** The plan merges findings
that carry the *same `Location`* — same file and same line, both present. The
alternative considered was title similarity (token-set Jaccard at 0.6), which
was rejected: it needs a threshold nobody can validate from the plan, it would
miss the observed clusters whose titles are worded differently, and it applies
a fuzzy rule to the one record that must not lose a finding. If the maintainer
prefers similarity, the merge predicate is one function in `DuplicateFindings`
and the rest of the PR is unchanged.

## ADRs

No new ADR. Two existing ones are amended — not optional, since each states the
opposite of what a PR here does:

- **ADR 0022** — PR 1 amends §1 (`:47`, "Severity stays, for ordering and
  labels only") and §3 (`:93–94`, "at the severity the consequence deserves",
  "Fix cost never lowers severity"); PR 2 amends §2 (`:66`, "Capped at 5
  rounds"). Dated amendments in the style of the existing entry at `:70`.
- **ADR 0008** — PR 3 corrects the glyph/colour table and the `⎿` and `·` rows.

---

# PR 1 — Findings: drop severity, keep location, one word for declined

## Behavior decisions

**`Severity` is deleted outright.** Orca is 0.x (AGENTS.md: no backwards
compatibility is owed anywhere), and after the confidence gate went the type
has one remaining job: labels and an ordering. Following the #156 precedent
(`d63b176b`), the type, its `Schema`/`JsonValueCodec`, the
`ReviewIssue.severity` field and the export go in one commit.

**Unresolved findings get `file:line` via the existing side-index, not a new
`IgnoredIssue` field.** `IgnoredIssue` is a field of `FixOutcome`
(`FixOutcome.scala:18`, `derives JsonData` at `:19`) — the *fixer* produces it.
A `location` field there would ask the fixer for something it has no reliable
way to fill and that `FixOutcome.reconcile` would then have to defend against.
Instead, `ReviewLoop`'s three `severities: Map[Title, Severity]` threads
(`:240` in `fixLoop`'s loop, `:947` in `run`'s loop, `:1020` in `runOnce`) plus
`announceExit`'s parameter (`:130`) become `locations: Map[Title, Location]`,
fed by `indexSeverities` (`:138–142`) renamed to `indexLocations` folding
`i.location` where present. `formatUnresolvedFindings` renders through
`locationLine` (`ReviewFormatting.scala:42–45`), which the finding display
(`:36`) and the fix prompt (`FixRequest.scala:58`) already share, so all three
render a location the same way. A finding whose reviewer gave no location
renders unprefixed — the same graceful degradation the severity map has today.

**No decline folding.** An earlier draft folded near-duplicate declines on a
normalised title. It cannot fire: `FixOutcome.reconcile` resolves every fixer
echo back to the canonical `ReviewIssue.title` (`FixOutcome.scala:93–104`), and
matches case- and whitespace-insensitively (`:87`, `:115–116`), so
`IgnoredIssue.title` is always a reviewer's exact title and identical-modulo-
case titles are already collapsed. The only case left is a genuine rewording,
which an exact rule cannot catch and a fuzzy rule must not (it would drop a
finding from the permanent record). Recorded in the spec's out-of-scope list.

**One word: `declined`.** `announceFixTurn`'s `Fixed N, ignored N`
(`ReviewLoop.scala:219`), `formatUnresolvedFindings`' `Unresolved findings (N)`
→ `Findings still open (N)`, and `cleanExitMessage` (`:83–84`). `No issues to
fix` is not suppressed but *replaced*: it fires precisely when findings are
still open, so it becomes `No new findings; N still declined` — one honest line
instead of a misleading one above a contradicting block.

**Reviewer prompts stop asking for a severity.** `initial-review.md:11` drops
"severity (Critical / Warning / Info)"; `:36–41`'s "at the severity that
consequence deserves" becomes "must always be reported", and the cost line
becomes "'One-line fix' describes cost. Never withhold or soften a finding
because the remedy is small." `re-review.md:11` mirrors it.

## File-by-file changes

| File | Change |
|---|---|
| `flow/.../review/Severity.scala` | Delete. |
| `flow/.../review/ReviewIssue.scala` | Drop `severity`. |
| `flow/.../review/ReviewLoop.scala` | `indexSeverities` → `indexLocations` (`:138–142`); the three `severities` threads (`:240`, `:947`, `:1020`) and `announceExit`'s parameter (`:130`); `declined` wording at `:219` and `:83–84`. |
| `flow/.../review/ReviewFormatting.scala` | `formatIssue` drops `[${issue.severity}]` (`:26`); `formatUnresolvedFindings` takes `Map[Title, Location]`, renders via `locationLine`, and is retitled (`:75–94`). |
| `flow/.../review/FixRequest.scala` | `renderIssue`'s exhaustive destructure loses the `severity` slot; header becomes `s"$key $title"` (`:52–62`). Compile forces this. |
| `flow/.../resources/.../initial-review.md` | Lines 11–14, 36–41. |
| `flow/.../resources/.../re-review.md` | Line 11. |
| `flow/.../resources/.../fix.md` | Two `[Warning]` occurrences (lines 7, 11) → key + title only. |
| `runner/.../exports.scala` | Remove `Severity` (`:77`) and its mention in the comment (`:53–54`). |
| `flows/review.sc` | Header (`:1`, `:18`); `reviewPrompt`'s ask (`:198`); `renderReport`'s `Severity.values` sectioning (`:224–227`); delete `renderSeverity` (`:229–234`). One flat list in reviewer/report order — the flow has no other ordering key. |
| `adr/0022-review-rework.md` | Dated amendment to §1 and §3. |

Tests — the string pins are enumerated because they are easy to miss:

- `ReviewLoopFixture.scala` — drop `severity` from the issue builder
  (mechanical across most review tests).
- `ReviewTypesTest.scala`, `JsonSchemaGenTest.scala` — drop severity
  decode/schema pins; keep nullable/additional-props pins.
- `ReviewLoopPromptsTest.scala:118, 125, 188` — severity-ask pins; replace with
  one asserting neither template mentions "Critical"/"Warning"/"Info"/
  "severity".
- `FixLoopTest.scala:230, 260` — severity-rendering pins.
- `FixLoopTest.scala:211` and `ReviewAndFixTest.scala:210, 247, 277, 314` —
  `Unresolved findings` exact-string pins, all five broken by the retitle.
- `flowtests/FlowCompilesTest.scala:184–185` — pins
  `List[(Severity, Option[Location])]`; becomes `List[Option[Location]]`.
- **Add**: an unresolved finding renders `at file:line`; one with no location
  renders bare; the clean-exit headline says how many are still declined.

Docs: `README.md:847` ("Issues carry severity, a `title`…") drops severity.

## Test plan

Unit only, no new fixtures. `sbt flow/test runner/test shell/test` green, zero
warnings, `scalafmtAll`. Verification greps: `grep -rni severity flow/src
runner/src flows/ README.md` returns nothing, and `grep -rn "Unresolved
findings\|ignored" flow/src` returns nothing outside `FixOutcome`'s own
`ignored` field name (which is the fixer's wire schema and stays).

## Acceptance criteria

- `Severity` is gone from source, exports, prompts, flows and README; the JSON
  schema reviewers are handed no longer has the field.
- Every entry in the closing findings block carries `at file:line` when the
  reporting reviewer gave one, rendered by the same helper the fix prompt uses.
- No user-facing line says "ignored" or "unresolved" of a finding; the
  clean-exit headline never claims there is nothing to fix while findings are
  still open.
- ADR 0022 §1/§3 amended.

**Size: M** — ~9 source files, ~8 test files, 3 prompts, 1 flow, README, 1 ADR
amendment.

---

# PR 2 — What the reviewers work with

## Behavior decisions

**Re-review payload: `TooLarge` carries the changed sections.** All of the
`ReReviewChanges` machinery lives in `ReviewLoopPrompts.scala` — the enum at
`:239–261`, `InlineThreshold` at `:270`, `of` at `:281–290`, `unchangedSince`
at `:299–309`, `fileSections` at `:316–321` — the same file that renders
`changesBlock`, so this is one file, not two. `of` already computes the
changed/unchanged split and discards the section *text*. Change the case to
`TooLarge(changedDiff: String, changed: List[String], unchanged: List[String])`
and have `changesBlock` (`:174–186`) send that diff followed by the unchanged
path list.

**Bounding needs a new paths-only helper.** `BoundedDiff.reviewPayload`
(`BoundedDiff.scala:107`) takes `(diff, changed: List[ChangedFile])` and its
trailer (`:187–216`) needs per-file line counts. `of` has only
`DiffSample(diff: String, paths: List[String])`
(`ReviewDiffSource.scala:11`) — no `ChangedFile`, no line counts. Add a
paths-only sibling in `BoundedDiff` that cuts on whole-`diff --git` boundaries
at the same byte budget and names the dropped paths in its trailer. This is
real scope the earlier draft did not budget; it is ~30 lines and keeps
`DiffSample` unwidened, which is the cheaper of the two options (widening it
would reach `GitTool.reviewChanges` and the scraped `Pinned` path at
`ReviewDiffSource.scala:132`).

**The no-delta fallback keeps today's behavior.** `of` at `:288` returns
`TooLarge(current.paths, Nil)` when the delta names no path — there is no
"changed section" to send, and sending the whole >16 KiB diff would inline up
to `ReviewThreshold` (128 KiB) into a resumed conversation every round, which
is exactly what `InlineThreshold` exists to prevent. That arm keeps sending
paths with "read them directly".

**Three scaladocs go false and must be corrected**, not just one:
`InlineThreshold`'s (`:270`, promises "the reviewer gets paths and opens the
files"), `ReReviewChanges.TooLarge`'s (`:243–251`, "Only paths are sent … so a
resumed conversation doesn't accumulate one copy of a large diff per round"),
and `BoundedDiff.ReviewThreshold`'s (`BoundedDiff.scala:43–46`, which restates
`InlineThreshold`'s purpose).

**`LastSent.PathsOnly` becomes `LastSent.SectionsOnly`.** `resumeReview`
(`ReviewLoop.scala:661–666`) already stores the whole diff for the next round's
comparison; only the name and the `AlreadySeen(PathsOnly)` wording
(`ReviewLoopPrompts.scala:191–194`) change. The comparison logic is correct —
do not touch it.

**Review scope: both templates.** `initial-review.md:3–5` says "do NOT survey
unrelated files… Focus your findings strictly on what the diff modifies and on
code that interacts directly with it", and `re-review.md:3–4` independently
says "Stay scoped to the change set under review; do not expand to unrelated
files". Both contradict the new rule and both must change — a reinforcing
paragraph in `re-review.md` is not enough. Keep the anti-survey intent, add:
unchanged code is in scope precisely when the change alters what it can be
handed. Add a section instructing the reviewer to name the assumptions the
change relaxes and find the code still relying on them. Pin both.

**Rounds 5 → 3, as literals.** `DefaultMaxIterations` is already 3
(`ReviewLoop.scala:49`); the `5` appears only in the five flows' final reviews
and in two `BuiltInFlowsTest` pins (`:100–112`, `:114–120`). Change the five
literals to `3` and merge the two now-identical cap tests into one. Do **not**
export `DefaultMaxIterations` to flow scripts: widening the public script API
to settle a test-pin problem is the wrong trade, and the existing comment
(`:104–107`) defending "each flow states its own number" stays true.

**Duplicate findings: merge on identical `Location`, at the fan-out join.** The
join is `collectRound` (`ReviewLoop.scala:798–814`). Merge two findings when
both carry a `Location` and it is equal (same file, same line); findings
without a location never merge. No similarity threshold — see the open decision
above. Two load-bearing constraints:

1. Merge only the `issues` field handed to `FixRequest`. `state =
   currentState.afterRound(contributions, …)` must keep the **pre-merge**
   per-reviewer results, because `narrowingAcrossRounds` retires a reviewer on
   `previous.reviewersWithIssues` (`ReviewerSelector.scala:228`;
   `ReviewerRoster.scala:48–49` filters `rr.issues.nonEmpty`) — a reviewer
   whose only findings were absorbed would otherwise read as silent and be
   retired.
2. Keys are minted per agent before the fan-out (`:730–742`) and each
   reviewer's findings are displayed as it finishes (`:782–789`), so "presented
   once" is already satisfied by the display being per reviewer. The survivor
   keeps the first reporter's key.

The merged entry names the other reviewers ("also reported by *scala-fp*,
*simplicity*") and keeps **one** description — appending N descriptions would
re-inflate the prompt the merge exists to shrink. Lives in a new
`private[review] DuplicateFindings.scala` of pure functions;
`ReviewLoop.scala` is under capture checking (`:10–11`), so keep the new file
free of tapir `derives` (it needs none) — the precedent is `FixRequest.scala`.

**`simple.sc` adopts the roster *and* the default selector.** It passes both
`buildReviewers(reviewAgent, List(review))` (`:43`) and `reviewerSelection =
ReviewerSelector.allEveryRound` (`:44`). Swapping only the roster would run all
8 reviewers (`Reviewers.scala:78–87`) every round. Both change: `reviewers =
allReviewers(reviewAgent)`, and the `reviewerSelection` argument is removed so
the loop's default applies. Delete the custom `Reviewer` val and its justifying
comment (`:22–33`); update the header (`:1`).

**Prompt logging at DEBUG, from the loop.** All three turns pass `emitPrompt =
false` (`ReviewLoop.scala:656`, `:693`, `:885`; `ReviewerSelector.scala:168`),
so no `UserPrompt` event exists for `LoggingListener` (`:25`) to mirror. Log
directly via `LoggerFactory.getLogger("orca.flow")` — the logger `OrcaLog`
makes non-additive, so nothing reaches the console. Per reviewer per round: one
line with reviewer name, round, `ReReviewChanges` case name, payload length and
file list; then the rendered prompt. Same for the fix prompt and the picker
request. Keep `emitPrompt = false` — flipping it would put a `▸` line per
reviewer per round on the console, exactly the noise PR 3 removes.

## File-by-file changes

| File | Change |
|---|---|
| `flow/.../review/ReviewLoopPrompts.scala` | `TooLarge` gains `changedDiff`; `of` builds it; `changesBlock`'s arms; `SectionsOnly` wording; two scaladocs. |
| `flow/.../BoundedDiff.scala` | New paths-only bounding helper; `ReviewThreshold` scaladoc. |
| `flow/.../review/ReviewLoop.scala` | `PathsOnly` → `SectionsOnly` (`:665`); `collectRound` merges duplicates into `issues` only; DEBUG logging in `resumeReview`/`firstReview`/`fix`. |
| `flow/.../review/DuplicateFindings.scala` | **New.** Grouping by `Location`, merged rendering. |
| `flow/.../review/ReviewerSelector.scala` | DEBUG log of the picker request (`:168`). |
| `flow/.../resources/.../initial-review.md` | Scope sentence (`:3–5`); invariant-consequence section. |
| `flow/.../resources/.../re-review.md` | Scope sentence (`:3–4`); reinforcing paragraph. |
| `flows/{implement,implement-interactive,implement-enhanced,issue-pr,issue-pr-bugfix}.sc` | `maxIterations = 5` → `3`; update each comment. |
| `flows/simple.sc` | Roster + default selector; delete the custom reviewer and comment; header. |
| `adr/0022-review-rework.md` | Dated amendment to §2. |

Tests: `ReviewChangeSetTest` (`TooLarge` carries bounded sections; the no-delta
arm still sends paths; a single oversized file degrades to a named-but-unshown
trailer); `ReviewLoopPromptsTest` (scope + invariant pins in *both* templates;
the `TooLarge` block contains diff text); new `DuplicateFindingsTest` (merge on
equal location; no merge without a location; no merge across lines; the merged
entry names both reviewers and carries one description); `ReviewAndFixTest`
(**the regression that matters**: a reviewer whose only finding was absorbed is
still selected next round); `BuiltInFlowsTest` (the two cap tests merged into
one).

## Test plan

Unit as above, plus one live `flows/simple.sc` run on
`examples/runnable/01-simple` — the only flow whose reviewer set changes shape,
and a picker round-trip on a one-file diff is the cheapest possible check.

## Acceptance criteria

- A resumed reviewer past the 16 KiB threshold receives the changed files' diff
  sections, bounded on whole-file boundaries, with unchanged paths listed
  after; the no-delta case still sends paths only.
- Both templates state that unchanged code relying on a relaxed assumption is
  in scope, and instruct naming the assumption. Pinned.
- Two reviewers reporting at the same `file:line` produce one `FixRequest`
  entry naming both; each reviewer's findings still appear under its own name;
  neither reviewer is retired by narrowing.
- Every final review states `maxIterations = 3`; `BuiltInFlowsTest` still pins
  that every fix-loop call states its cap.
- `simple.sc` runs `allReviewers` under the default selector; any run's trace
  contains the rendered prompt and payload size for every reviewer round and
  every fix turn.
- ADR 0022 §2 amended.

**Size: L** — payload restructure + bounding helper + dedup component + prompt
rewrites + six flows + ~5 test files.

---

# PR 3 — What the run shows while it works

Read `adr/0008-terminal-output-design.md` and `adr/0009-announce-typeclass.md`
first. This PR implements against ADR 0008 and corrects it.

## Behavior decisions

**Read-only tool calls render as one constant line, and the existing
repeat-collapse does the rest.** In `TerminalEventListener`'s `ToolUse` arm,
a read-only call renders as `⏺ read` with no arguments.
`TerminalOutputState.writeLog`/`flushRepeats` (`TerminalOutput.scala:230–245`)
already folds a run of identical lines into one line plus `⎿ ×12`, in **both**
animated and piped mode. That is the whole mechanism: no status-line routing,
no stage-label restore, no separate non-animated count line, no new cursor or
newline discipline. What is lost is the filename shown transiently — and
`LoggingListener` (`:28–29`) records every call at DEBUG in the trace file
whose path the banner prints.

Classification: a name set in `orca.runner.terminal` covering the backends'
read tools (Read/Grep/Glob and the codex/gemini/opencode/pi equivalents),
documented as best-effort display-only, with **an unrecognised name treated as
mutating** — the safe direction is to print. No environment escape hatch: the
trace file already serves anyone who wants every call.

**Reviewer prose: withhold the closing turn of schema-carrying autonomous
calls.** One line in `Conversations.autonomousClosingProse` (`:39–46`):
`StructuredOutputMode.Tool` returns `Withhold`, not `Render`. `TurnBuffer`
already implements the state machine — `onActivity()` releases a parked turn
when the next turn opens (`:79–81`), `finishNormally()` drops only the closing
turn (`:102–104`), `finishAbnormally()` flushes it (`:110–113`) so a failed
call keeps its prose.

**This is driver-dependent and must be verified per driver before the
acceptance criterion is pinned.** The withhold fires only if the closing prose
and the schema-exit tool call sit in the *same* turn. `AssistantToolCall.
opensTurn` is `true` (`ConversationEvent.scala:89`), so any driver that emits
an `AssistantTurnEnd` between the prose and the exit call releases the prose
and renders it. A synthetic drain test would pass by construction and prove
nothing. Check each `StructuredOutputMode.Tool` backend's event order against a
recorded session before claiming the behaviour. Correct `Conversations`'
scaladoc, which currently claims a `Tool`-mode backend "never streams it as
prose, so nothing is withheld".

**Truncation budgets account for the prefix.** `MaxAssistantMessageLength = 100`
and `MaxInlineInputLength = 120` are applied to the body, then indent, glyph
and `AgentAttribution` prefix are prepended (`TerminalEventListener.scala:93–99`,
`ToolCallLine.scala:28–30`). Pass an effective budget: `budget -
currentIndent.length - glyphWidth - attributionWidth`, floored at **24
characters** so a deeply nested line still shows something. Note
`TerminalEventListenerTest.scala:154` currently *allows* +10 overflow and must
be tightened.

**Middle-truncation for long absolute paths.** `ToolInputSummary.relativise`
(`:133–140`) deliberately leaves out-of-workDir paths absolute — ADR 0008's
"external file access remains visually obvious" — and that stays. `:97–98`
end-truncates, which eats the filename. Add `Text.middleTruncate(s, max)` and
apply it in the `HeadlineKind.Path` branch so an over-budget absolute path
renders head + `…` + tail, keeping the leading `/` signal.

**Failures: attribute the cause.** Add `agent: Option[String] = None` to
`OrcaEvent.Error`, consistent with its `ToolUse`/`AssistantMessage` siblings,
so a per-agent failure is distinguishable from the stage failure that follows
it — today both render as bare `✖` lines. Do **not** remove the
`printStackTrace` calls at `FlowLifecycle.scala:84` and `:113`: they are
already gated on `debug` (`--verbose`/`ORCA_DEBUG`), so they never reach a
default-path user, and removing them would leave `--verbose` meaningless.
Do **not** add a wrapper-collapse rule to `TextUtil.throwableMessage`
(`:9–13`): it does not walk causes at all today, so the proposed rule could
only shorten a line, never surface a hidden cause — the spec sentence it was
meant to serve is satisfied by attribution alone.

**Consistency.** Filter orca's own `orca:`-prefixed commits in
`TerminalEventListener`'s `Step` arm on the message shape `Committed: orca: `,
not in `GitTool` (the trace keeps them). Compress `EnvCookieSweep.describe`
(`:116–117`) to the command's basename plus pid — the pid is the actionable
part; the JDK path is not. Leave the `I1.1` finding keys as they are: they
cross-reference the fixer's visible "Fix I1.1" narration
(`ReviewFormatting.scala:17–19`) and earn their place.

**ADR 0008 corrections**, all verified: `▶` is `Magenta ++ Bold`
(`TerminalEventListener.scala:175`), documented cyan (`adr/0008:88`); `⏺` is
`Yellow ++ Bold` (`ConversationRenderer.scala:213`), documented blue (`:92`);
the `·` thinking row is fiction — `showThinking` exists nowhere and thinking
deltas are discarded (`ConversationRenderer.scala:75`, `Conversations.scala:148`);
`⎿` in an autonomous run is only `RepeatGlyph` (`TerminalOutput.scala:283`),
the tool-result row applying to `ConversationRenderer`'s interactive path only.
**Keep `StageDoneGlyph`** (`TerminalEventListener.scala:158`) — it is not dead:
three tests assert it *absent* to pin ADR 0008's "no `✔` in the log" invariant
(`TerminalEventListenerTest.scala:41, 86, 368`). Give it a scaladoc saying so.

## File-by-file changes

| File | Change |
|---|---|
| `tools/.../events/OrcaEvent.scala` | `Error` gains `agent: Option[String] = None`. |
| `tools/.../backend/Conversations.scala` | `autonomousClosingProse`: `Tool` → `Withhold`; scaladoc. |
| `tools/.../sweep/EnvCookieSweep.scala` | `describe` (`:116–117`) → basename + pid. |
| `runner/.../terminal/TerminalEventListener.scala` | Constant `⏺ read` line + classification; prefix-aware budgets; `orca:` commit filter; `Error` attribution; `StageDoneGlyph` scaladoc. |
| `runner/.../terminal/Text.scala` | `middleTruncate`. |
| `runner/.../terminal/ToolInputSummary.scala` | Middle-truncate long absolute paths (`:97–98`, `:133–140`). |
| `runner/.../LoggingListener.scala` | Handle the widened `Error`. |
| `adr/0008-terminal-output-design.md` | Corrections above. |

Tests: `TerminalEventListenerTest` (a run of read-only calls collapses to one
line plus `⎿ ×N` in both modes; an unknown tool name still prints in full;
budgets produce one line at depth 3 with an agent prefix — tighten `:154`;
`orca:` commits absent; error attribution), `ToolInputSummaryTest` (middle
truncation, leading `/` preserved), plus the per-driver verification above
recorded as a note rather than a synthetic test.

## Test plan

Unit as above. One live `implement.sc` run for a visual check, with a stated
pass bar: read-only tool lines fall from ~48% of output to under 10%, and no
line in the capture exceeds 100 columns.

## Acceptance criteria

- Read-only tool calls do not appear one per line; the trace still records
  every call; an unrecognised tool name is printed in full.
- No line exceeds its effective budget once indent, glyph and agent prefix are
  counted; no absolute path renders with its filename cut.
- A reviewer's closing prose does not appear for the drivers verified above;
  its mid-turn narration does; a failed call still shows its prose.
- A per-agent failure line names the agent.
- ADR 0008's glyph table matches `StepGlyphStyle` and `ToolNameStyle` exactly,
  drops the `·` row, and states what `⎿` means in an autonomous run.

**Size: M** — ~8 files, most edits small, plus an ADR correction and a live
visual check.

---

# PR 4 — What the run tells the user at the end

## Behavior decisions

**Closing summary: emitted from `teardownSuccess`, before the bookkeeping
commit, and after the branch's fate is known.** `teardownSuccess`
(`FlowLifecycle.scala:1093–1123`) removes the log, commits, pushes, then hands
off to `finishBranch` (`:1122–1129`), which may **delete** a throwaway feature
branch and return HEAD to the start branch. Compute the summary's facts at the
top (before the commit, or the file count is off by one) but **emit it after
`finishBranch` returns**, and name the branch the user is actually left on —
otherwise a throwaway run's summary points at a branch that no longer exists.
`teardownSuccess` takes only `(git, setup, returnToStartBranch)` and has no
emitter: add an `emit: OrcaEvent => Unit` parameter (it is `private[orca]`;
four test call sites at `FlowLifecycleTest.scala:2907, 2985, 2994, 3007` need
`_ => ()`).

**Contents: branch, files changed, next command.** `setup.startingCommit`
(`:230`) is `Option[CommitHash]` and is filtered to `None` on a resume whose
recorded commit is no longer an ancestor of HEAD (`:689–690`), so the
file-count line needs a `None` arm — omit the count rather than guess. No
commit count: it would need a new `GitTool.commitsSince` (trait + `OsGitTool` +
test fakes) for the least actionable of the four facts. **No findings tally** —
see the spec's out-of-scope note: a run has several review loops, and
`IgnoredIssue` carries no structured cause, so the numbers would be both
double-counted and mislabelled.

**Branch named on resume.** `createBranch` emits "Switched to a new branch"
(`GitTool.scala:492`) and `checkout` emits "Switched to branch" (`:501`), but a
resumed run binding an existing branch takes neither path. Emit a Step from
`setup`'s resumed arm naming the branch and the recorded starting commit.

**Resume: one line, not two per stage.** `Flow.resumeFrom` (`:74–78`) emits
`StageStarted` → `Step("Resuming '$name'…")` → `StageCompleted`. Drop the
per-stage `Step` and emit one run-level Step from `setup`'s resumed arm:
"resuming: N stages already recorded, tree reset to `<short>`; the interrupted
stage's uncommitted work was discarded". Keep `StageStarted`/`StageCompleted`:
`stack` is pushed at `TerminalEventListener.scala:50` and popped at `:57` with
`currentIndent = "  " * stack.length` (`:108`), so removing `StageStarted`
alone would pop the enclosing stage's indent.

**Coder told the same.** `progressPreamble` (`Session.scala:388–394`) is what
primes a re-seeded session. Extend it to state that uncommitted work from the
interrupted stage was discarded and name the commit the tree sits at, passing
the commit in explicitly (`fc.git.headCommit()` at prime time) rather than
reading it inside. Document the caveat: the preamble applies only when the
backend conversation is fresh or lost — the common resume case, not all of
them.

**Resume hint on failure: edit the existing Step.** The recovery Step
(`FlowLifecycle.scala:95–100`) already says "so a re-run resumes from the last
completed stage"; the only missing fact is the command. Extend that string —
no new emit.

## File-by-file changes

| File | Change |
|---|---|
| `runner/.../FlowLifecycle.scala` | `teardownSuccess` takes `emit`, emits the closing block after `finishBranch`; resumed arm emits branch + resume Step; recovery Step names the command. |
| `runner/.../ClosingSummary.scala` | **New.** Renders the block. |
| `flow/.../Flow.scala` | Drop the per-stage resume `Step` (`:74–78`). |
| `flow/.../Session.scala` | `progressPreamble` states the reset + commit. |
| `runner/.../flow.scala` | Wire the emitter through to `teardownSuccess`. |

Tests: `FlowLifecycleTest` (closing block names the branch the run ends on,
including the throwaway-deleted case; the `startingCommit = None` arm omits the
file count; resumed arm emits the branch Step; recovery Step names the
command), `SessionTest` (preamble states the reset), `FlowTest` (one line per
replayed stage).

## Acceptance criteria

- A successful run ends with a block naming the branch the user is left on, the
  files changed (or omitting the count when no base is recorded), and the next
  command; a resumed run names its branch.
- A replayed stage prints one line, not two; the run says once what it is
  resuming from and that the interrupted stage's work was discarded.
- A failure's recovery message names the command that resumes.

**Size: M** — ~5 files plus tests; the branch-fate ordering is the one subtle
part.

---

# PR 5 — What the cost summary claims

## Behavior decisions

**Attribution: name the role agents in `RoleAgents.resolveOne`, not the
flows.** Naming in the five shipped flows would leave every user-written flow
and the shell with the same `main` bucket, and the runtime's own cheap
one-shots (`defaultCommitMessage`, branch naming) are not in a flow at all.
`Agent.cheap` preserves both name and role (`Agent.scala:139` →
`BaseAgent.scala:57–58`), so naming the role agent fixes the cheap sub-calls
for free — the actual reported symptom.

Guard against clobbering a name a programmatic override set deliberately
(`flow(codingAgent = Some(_.claude.withName("bob")))`): rather than a hardcoded
default-name set that can go stale, apply `.withName(role)` only when the
resolved agent still carries the name its own backend's wired agent carries —
`a.backendTag.map(agents.agentFor).exists(_.name == a.name)`, using the
`agentFor`/`backendTag` already at `RoleAgents.scala:167–171`. (Note pi's
default is `"pi"`, not `"main"` — which is exactly why a hardcoded set is the
worse option.)

Names: use `resolveOne`'s existing labels — `planning`, `coding`, `review` —
rather than inventing a second vocabulary, and reference `ReviewerPrompts.Role`
where the role *tag* is meant.

**Picker call carries the review role.** `ReviewerSelector.agentDriven`'s
parameterless form resolves `ctx.reviewAgent.cheap` (`:119`) with no role and
no name. Add both, so the by-role subtotal is right and the by-agent line
separates the $0.11/turn picker from the reviewers. `Lint` does the same thing
(`ReviewLoop.scala:752`, its `.cheap` applied at `:568`, on the
`Configured.FromSettings` branch), so this makes the two consistent.

**Detail: compact only.** `CostTracker.summary` (`:140–169`) prints by-agent,
by-model and by-role. Print total + by-role + by-model. The per-agent view is
recoverable from the run's cost log — `CostRecord.Turn` carries `agent`,
`role`, `stage`, `attempt`, and its scaladoc (`CostLog.scala:62–64`) states
by-agent is meant to be a fold over those lines, which the model field below
completes. No enum, no env var, no constructor parameter, no
byte-identical-to-today test. The estimate marker and unpriced legend
(`:171–198`) are untouched.

**Persisted log records the model.** `CostRecord.Turn` (`CostLog.scala:71–81`)
gains `model: Option[String]` — `Option` because `TokensUsed.model` already is
(`OrcaEvent.scala:81`). Purely additive; AGENTS.md exempts `CostRecord` from
the golden-fixture rule (`:335–336`). No default value (the 0.x no-defaults
rule); the one writer (`RunManifestWriter.scala:174–184`) passes it.

**Prices.** `claude-mythos-5` is genuinely absent and would fall through to
unpriced: add `Model("claude-mythos-5") -> anthropic(10)` ($10/$50 matches
`anthropic`'s 5× output derivation, `Pricing.scala:76–92`), with a comment that
it is invitation-only so no backend default pins it and `DefaultModelsPricedTest`
will not cover it. Leave `claude-sonnet-5` at `anthropic(3)` (`:195`): the $2/$10
rate is promotional and its own comment (`:193–194`) already records that it
ends 2026-08-31, days away. Bump `lastUpdated` (`:266`) to the PR's date. Do
not touch the 1-hour cache-write multiplier — its scaladoc records the
measurement behind it.

## File-by-file changes

| File | Change |
|---|---|
| `flow/.../events/Pricing.scala` | Add `claude-mythos-5`; bump `lastUpdated`. |
| `flow/.../events/CostTracker.scala` | `summary` drops the by-agent section. |
| `runner/.../RoleAgents.scala` | Conditional `.withName(role)` in `resolveOne`, guarded by the wired-agent name check. |
| `runner/.../manifest/CostLog.scala` | `CostRecord.Turn.model: Option[String]`. |
| `runner/.../manifest/RunManifestWriter.scala` | Pass the model through (`:174–184`). |
| `flow/.../review/ReviewerSelector.scala` | Picker carries the review role + its own name (`:119`). |

Tests: `PricingTest` (mythos-5; alias and dated-suffix paths still work);
`CostTrackerTest` (the compact summary sums to the total; both legends
survive); `RoleAgentsTest` (each role's agent is named; a programmatic
override's explicit name survives; a pi-backed role is named too);
`ReviewerSelectorTest` (picker turn carries the review role); manifest tests
(the model round-trips).

## Acceptance criteria

- The by-role breakdown separates planning, coding and review, and the picker's
  spend lands under review, not under `main`.
- `claude-mythos-5` prices; `DefaultModelsPricedTest` still green.
- The summary is total + by-role + by-model; the estimate marker and unpriced
  legend still print when they apply.
- Every `CostRecord.Turn` carries the model, so a by-model *and* a by-agent
  aggregate are both reproducible from the file alone.

**Size: S/M** — small code; the role-naming guard is the only part needing real
test coverage.

## A note on citations

Several design rules invoked here live in the `direct-style-scala` skill, not
in AGENTS.md — in particular "keep APIs lawful: no hidden `Clock`/env
dependencies". AGENTS.md separately forbids display code mirroring the
production resolver (`:299–301`) and boolean modes (`:290–292`), and exempts
`CostRecord` from the golden-fixture rule (`:335–336`). Cite each to its
actual home.
