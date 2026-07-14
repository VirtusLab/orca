# Epic 2: Settings Data Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans.
> Spec: `adr/0019-project-stack-settings.md` (§ "The settings file", § list
> semantics, § API). Roadmap: `2026-07-14-stack-settings-roadmap.md`.
> Epic 1 landed: `orca.OrcaDir.{root,settingsPath,ensureRoot,ensureCache}`,
> `FlowContext.workDir: os.Path` are available.

**Goal:** the pure data layer — `StackSettings`, the strict key=value
parser and the writer (round-tripping), and the `Lint` multi-command
upgrade. No lifecycle involvement.

**Architecture:** `StackSettings` sits in flow's top-level `orca` package
(exported to scripts). Parser/writer live in a new `orca.settings` package
in the flow module — public (the runner module consumes them in Epics 3–4).
`Lint` changes in place (capture-checked file — keep its language imports
and idioms).

## Global constraints

- Parser is NOT `java.util.Properties` — hand-rolled strict line format.
- Comments: present-tense facts; update stale ones you touch.
- Zero warnings; per-task commits; `sbt --client scalafmtAll` before each.

---

### Task 1: `StackSettings` + parser

**Files:**
- Create: `flow/src/main/scala/orca/StackSettings.scala`
- Create: `flow/src/main/scala/orca/settings/SettingsFile.scala`
- Create: `flow/src/test/scala/orca/settings/SettingsFileTest.scala`
- Modify: `runner/src/main/scala/orca/exports.scala` (export `StackSettings`)

**Interfaces (produces — Epics 3/4/5 rely on these exact shapes):**
```scala
package orca

/** Resolved per-project tooling commands (ADR 0019). Each command runs via
  * `bash -c`; an empty list means the task is disabled/unknown. */
case class StackSettings(
    format: List[String] = Nil,
    lint: List[String] = Nil,
    test: List[String] = Nil
)
object StackSettings:
  val empty: StackSettings = StackSettings()
```
```scala
package orca.settings

import orca.StackSettings

/** Strict line format for `.orca/settings.properties`: `#` comments,
  * `key = value` with the value taken verbatim (trimmed) after the first
  * `=`, repeated keys append in file order, empty value ≡ omitted. */
object SettingsFile:
  val ValidKeys: Set[String] = Set("format", "lint", "test")
  /** Left = human-readable problem naming the offending line and the valid
    * keys (the lifecycle aborts with it before any tree mutation). */
  def parse(content: String): Either[String, StackSettings]
```

Parse rules (each is one test): blank lines and lines whose first non-space
char is `#` are skipped; a non-comment line without `=` is an error naming
the line number and expected shape; unknown key is an error naming the key
and `ValidKeys`; value is everything after the FIRST `=`, trimmed (so
commands containing `=` survive, e.g. `FOO=bar cargo check`); empty value
after trim is silently dropped; repeated keys append in order; keys are
case-sensitive.

- [ ] **Step 1:** Write `SettingsFileTest` covering each rule above plus a
      round-trip placeholder (ignored/pending until Task 2).
- [ ] **Step 2:** `sbt --client "flow/testOnly orca.settings.SettingsFileTest"`
      — FAIL (missing classes).
- [ ] **Step 3:** Implement `StackSettings` + `SettingsFile.parse`; export
      `StackSettings` from `exports.scala` (place near the `Lint` export,
      whose comment explains why call-site types are exported).
- [ ] **Step 4:** Tests PASS; `runner/compile` green (export resolves).
- [ ] **Step 5:** Format + commit:
      `git commit -m "StackSettings + strict settings-file parser (ADR 0019)"`

### Task 2: writer

**Files:**
- Modify: `flow/src/main/scala/orca/settings/SettingsFile.scala`
- Modify: `flow/src/test/scala/orca/settings/SettingsFileTest.scala`

**Interfaces (produces — Epic 4's discovery write consumes this exactly):**
```scala
enum SettingsEntry:
  /** `key = command` with an optional trailing `# comment` (evidence). */
  case Command(key: String, command: String, comment: Option[String])
  /** Rendered commented-out: `# key =   (reason)`. */
  case Unset(key: String, reason: String)

object SettingsFile:
  val Header: String // the two ADR header comment lines
  def render(entries: List[SettingsEntry]): String
```

Render format, pinned by tests (ADR's example shape):
```
# orca stack settings — edit freely, commit with the project.
# Delete this file to re-run auto-discovery.
format = cargo fmt    # Cargo.toml (rustfmt ships with the toolchain)
lint = cargo check --tests    # compiles main+test code, runs nothing
# test =   (no test evidence found)
```
(Exact separator between command and comment: four spaces then `# ` — pin
it in one test and keep parser-compatibility: the parser must NOT strip
trailing `# ...` from values — a `#` mid-line is part of the command
(`bash -c` handles quoting), so the WRITER only appends comments to lines
whose command contains no `#`; when the command contains `#`, drop the
comment. Add a parse test proving `format = echo '#1'` keeps the whole
value, and a render test proving comment-dropping.)

- [ ] **Step 1:** Un-pend the round-trip test:
      `parse(render(entries))` yields exactly the `Command` entries'
      commands per key (Unset lines invisible to the parser); add the two
      `#`-handling tests above.
- [ ] **Step 2:** FAIL → implement `render` → PASS.
- [ ] **Step 3:** Format + commit:
      `git commit -m "Settings-file writer with evidence comments, parser round-trip"`

### Task 3: `Lint` multi-command

**Files:**
- Modify: `flow/src/main/scala/orca/review/Lint.scala` (case class +
  `lint(...)` function; keep the captureChecking language imports and the
  InlineLintThreshold structure)
- Modify: `flow/src/main/scala/orca/review/ReviewLoop.scala:461-468` (the
  `lint(l.command, labelled)` call becomes `lint(l.commands, labelled)`)
- Modify: `flow/src/test/scala/orca/review/LintTest.scala`,
  `ReviewAndFixTest.scala` (fixture constructors),
  `runner/src/test/scala/flowtests/FlowCompilesTest.scala` (canaries:
  `Lint("cmd", agent)` → `Lint(List("cmd"), agent)`)

**Interfaces (produces):**
```scala
case class Lint(commands: List[String], agent: Agent[?])

def lint(
    commands: List[String],
    agent: Agent[?],
    instructions: String = ReviewLoopPrompts.SummariseLint
)(using ctx: FlowContext, ev: InStage): ReviewResult
```

Behavior (each its own test):
- Each command runs via `bash -c` in order, `mergeErrIntoOut = true`,
  `check = false` (as today).
- Combined text: per command a labeled block —
  `$ <command>   (exit <status>)` newline, its trimmed output, blank line
  between blocks. Blocks with empty output still appear as the label line
  only (the summariser sees "ran, empty, exit N").
- Short-circuit to `ReviewResult.empty` WITHOUT any LLM call only when
  EVERY command's output is empty AND every exit status is 0. (A silent
  nonzero exit must reach the summariser — a linter can fail with no
  stdout.) NOTE: this refines the ADR's "skipped only when every command's
  output is empty" with the exit-status guard; record the refinement in the
  final report.
- The status hint sentence in the prompt updates: statuses are per-command
  in the labels; keep the "zero usually means nothing to report" guidance.
- Spill threshold (`InlineLintThreshold`) applies to the combined text;
  spill file content = the combined labeled text.
- `Lint(Nil, agent)` is not constructible-meaningful: add a `require`? NO —
  make it unrepresentable later via `Configured` (Epic 5). For now
  `lint(Nil, ...)` returns `ReviewResult.empty` without running anything
  (one test).

- [ ] **Step 1:** Rewrite `LintTest` scenarios for the list semantics above
      (existing single-command tests become one-element lists; add
      two-command labeling, all-empty-zero short-circuit, empty-output-
      nonzero-exit reaches summariser, Nil no-op).
- [ ] **Step 2:** FAIL → implement → PASS (`flow/test`).
- [ ] **Step 3:** Fix the ReviewLoop call site + fixtures +
      FlowCompilesTest; `sbt --client "runner/Test/compile flow/test"` then
      `sbt --client "runner/test"` — PASS, zero warnings.
- [ ] **Step 4:** Format + commit:
      `git commit -m "Lint runs command lists with labeled outputs, one summariser pass"`

### Task 4: full-build gate

- [ ] `sbt --client test` all green; `git status` clean; report per-task
      SHAs, deviations, and the exact rendered-file format Epic 4 will
      write.

## Self-review notes

ADR coverage: repeated-key lists + empty-value semantics (T1), header +
evidence comments + unset-with-reason rendering (T2), labeled concatenation
+ per-command exit statuses + all-empty short-circuit + spill-on-concat
(T3). `Configured`/reviewAndFixLoop defaults: Epic 5. File reading at
lifecycle: Epic 3.
