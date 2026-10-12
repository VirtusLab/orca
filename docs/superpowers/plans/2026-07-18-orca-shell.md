# Orca Shell Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement ADR 0021 (`adr/0021-orca-shell.md`): the `orca-shell` interactive terminal program — wizard, flow discovery/view/edit/run, create-a-flow, session continuation — as a new sbt module, plus the library-side session event + run manifest.

**Architecture:** New `shell` module (published `org.virtuslab::orca-shell`, main `orca.shell.Main`) depending on `runner`. Flows run as tty-inheriting `scala-cli run` children with the shell's orca version forced via `--dep`. The one library change: a `SessionCommitted` event emitted at the Agent layer and a runner-side listener writing per-run JSON manifests under `.orca/cache/runs/`.

**Tech Stack:** Scala 3.8.4 / JVM 21, direct-style + Ox, os-lib, jsoniter, JLine 3.30.x + jline-console-ui, fansi, munit.

## Global Constraints

- Every decision detail is in `adr/0021-orca-shell.md`; research files under `docs/research/shell/` carry verified API facts (03 = ConsoleUI/highlighter/editor, 05 = discovery, 06 = wizard/settings, 08 = manifest schema). Read the ADR section named in each epic before implementing.
- Follow the `direct-style-scala` skill conventions and the repo comment style: comments state present-tense facts and constraints only — no Scala tutorials, no plan/PR references.
- scalacOptions are strict (`-Wunused:all`, `-Wvalue-discard`, `-Wnonunit-statement`); code must compile warning-clean.
- `runner` tests are forked and serialized (build.sbt) — keep new runner tests compatible; `shell` tests follow `commonSettings` defaults.
- The published `orca` library API stays source-compatible except where the ADR names a change; `runner` gains no dependency on `shell`.
- Version pins live in `project/Dependencies.scala`; jline moves 3.28.0 → 3.30.15 for ALL modules (shared pin).
- Windows is a non-goal (existing `bash -c` contract).
- Commit after every task; message style matches `git log` (`Shell: <what>` / imperative, ending with the Claude trailer).

## Epic structure & per-epic review

Implement epics in order; each leaves the build green (`sbt compile test` — scope to touched modules while iterating, full `sbt test` at epic end). After each epic, run the review agents listed for it; fold fixes in before the next epic.

| Epic | Review agents |
|---|---|
| 1 UI foundation | code-functionality-reviewer, scala-fp-reviewer, code-structure-reviewer |
| 2 Wizard & settings | code-functionality-reviewer, scala-fp-reviewer, test-reviewer |
| 3 Discovery, view & edit | code-functionality-reviewer, security-reviewer (editor spawn, path handling), simplicity-reviewer |
| 4 Flow launcher | code-functionality-reviewer, security-reviewer (subprocess/signals), scala-fp-reviewer |
| 5 Session events & manifests | code-functionality-reviewer, scala-fp-reviewer, test-reviewer |
| 6 Continue-session | code-functionality-reviewer, simplicity-reviewer |
| 7 Packaging & docs | readability-reviewer (README/install.sh), code-functionality-reviewer |

---

## Epic 1 — Module skeleton & terminal UI foundation (ADR §1, §3)

### Task 1.1: `shell` sbt module + version plumbing

**Files:**
- Modify: `project/Dependencies.scala` (jline 3.28.0 → 3.30.15; add `jlineConsoleUi`)
- Modify: `build.sbt` (new `shell` project; add to root aggregation if the root project lists modules explicitly — it aggregates via `dependsOn`? check: root is `orcaRoot in file(".")`; sbt auto-aggregates all `lazy val` projects, nothing to add)
- Create: `shell/src/main/scala/orca/shell/Main.scala`
- Create: `shell/src/main/scala/orca/shell/ShellVersion.scala`
- Test: `shell/src/test/scala/orca/shell/ShellVersionTest.scala`

**Interfaces:**
- Produces: `object ShellVersion { def value: String /* manifest Implementation-Version or "dev" */; def isRelease: Boolean /* no '+', not "dev" */ }`
- Produces: `object Main { def main(args: Array[String]): Unit }`

- [ ] **Step 1: Dependencies.** In `project/Dependencies.scala`: set `val jline = "3.30.15"` in `V`; add
```scala
// The org.jline:jline bundle jar already contains the reader, terminal,
// builtins (SyntaxHighlighter, Less) and style classes; console-ui's modular
// transitives would duplicate them, so exclude everything org.jline.
val jlineConsoleUi =
  ("org.jline" % "jline-console-ui" % V.jline).excludeAll(
    ExclusionRule(organization = "org.jline")
  )
```
- [ ] **Step 2: build.sbt.** After the `runner` project:
```scala
lazy val shell = (project in file("shell"))
  .dependsOn(runner, tools % "test->test")
  .settings(commonSettings)
  .settings(
    name := "orca-shell",
    Compile / mainClass := Some("orca.shell.Main"),
    libraryDependencies ++= Seq(osLib, jsoniter, jsoniterMacros, ox, jline, jlineConsoleUi, fansi)
  )
```
- [ ] **Step 3: Failing test** (`ShellVersionTest`): `assertEquals(ShellVersion.value, "dev")` (class-dir run has no manifest) and `assert(!ShellVersion.isRelease)`; plus `ShellVersion.isReleaseOf("0.0.18")` cases if you make it a pure function — prefer `def isRelease(v: String): Boolean` pure + `def isRelease: Boolean = isRelease(value)` so `isRelease("0.0.18+5-abc") == false`, `isRelease("dev") == false`, `isRelease("0.0.18") == true` are testable.
- [ ] **Step 4:** `sbt shell/test` → FAIL (missing class), then implement `ShellVersion` (OrcaBanner pattern: `Option(getClass.getPackage.getImplementationVersion).getOrElse("dev")`) and a `Main` that prints `orca shell <version>` and exits 0.
- [ ] **Step 5:** `sbt shell/test` → PASS; `sbt shell/run` prints the line. Commit: `Shell: new module skeleton, shared jline 3.30.15`.

### Task 1.2: `ShellUi` — prompts with tty gate and numbered fallback

**Files:**
- Create: `shell/src/main/scala/orca/shell/ui/ShellUi.scala` (trait + models)
- Create: `shell/src/main/scala/orca/shell/ui/ConsoleUiShell.scala` (ConsoleUI-backed)
- Create: `shell/src/main/scala/orca/shell/ui/NumberedUi.scala` (readLine fallback)
- Test: `shell/src/test/scala/orca/shell/ui/NumberedUiTest.scala`

**Interfaces (produced, used by every later epic):**
```scala
case class Choice[A](value: A, label: String, description: Option[String] = None,
                     disabledReason: Option[String] = None)
enum UiOutcome[+A]:
  case Selected(value: A)
  case Cancelled          // ESC / Ctrl-C at a prompt: back to previous menu

trait ShellUi:
  def select[A](title: String, choices: List[Choice[A]], preselect: Option[A] = None): UiOutcome[A]
  def confirm(question: String, default: Boolean): UiOutcome[Boolean]
  def input(prompt: String, default: Option[String] = None): UiOutcome[String]

object ShellUi:
  /** ConsoleUI when stdin+stdout are a tty, NumberedUi otherwise (ConsoleUI
    * NPEs on non-tty stdin — research 03 skeptic). */
  def make(terminal: org.jline.terminal.Terminal): ShellUi
```
- [ ] **Step 1: Failing tests for `NumberedUi`** (constructor takes `in: BufferedReader, out: PrintStream` for testability): select renders numbered rows (disabled rows shown with reason, not selectable), accepts `2\n`, re-prompts on garbage, `Cancelled` on EOF; confirm accepts empty → default; input returns default on empty when default set.
- [ ] **Step 2:** `sbt shell/test` → FAIL. Implement `NumberedUi` (pure loop over `in.readLine()`, `null` → `Cancelled`).
- [ ] **Step 3:** Implement `ConsoleUiShell` over `org.jline.consoleui.prompt.ConsolePrompt(terminal)`: build `ListPromptBuilder` per select (disabled choices rendered into the label, filtered from selection), map ESC/`UserInterruptException` → `Cancelled`. Implement `ShellUi.make` gating on `terminal.getType != Terminal.TYPE_DUMB && System.console() != null`.
- [ ] **Step 4:** `sbt shell/test` → PASS. Commit: `Shell: ShellUi with ConsoleUI prompts and numbered non-tty fallback`.

### Task 1.3: Main menu loop

**Files:**
- Create: `shell/src/main/scala/orca/shell/MainMenu.scala`
- Modify: `shell/src/main/scala/orca/shell/Main.scala`
- Test: `shell/src/test/scala/orca/shell/MainMenuTest.scala`

**Interfaces:**
```scala
enum MenuItem:
  case RunFlow, ViewFlow, EditFlow, CreateFlow, ContinueSession, Reconfigure, Exit
object MainMenu:
  /** Fixed ADR §3 order; `continueDisabledReason` non-None renders the item
    * disabled. */
  def choices(continueDisabledReason: Option[String]): List[Choice[MenuItem]]
```
- [ ] **Step 1: Failing test:** `choices(None)` yields the 7 items in ADR order all enabled; `choices(Some("no sessions recorded"))` disables only `ContinueSession` with that reason.
- [ ] **Step 2:** Implement; wire `Main` to loop `ui.select("orca shell", MainMenu.choices(...))` with stub handlers (each prints "not implemented yet" except Exit) — every later epic replaces one stub.
- [ ] **Step 3:** `sbt shell/test` → PASS; manual smoke `sbt shell/run` in a terminal. Commit: `Shell: main menu loop`.

**Epic 1 review gate:** run the epic-1 reviewer roster on the `shell/` diff.

---

## Epic 2 — Welcome wizard & settings write (ADR §4)

### Task 2.1: Global settings write/update in `SettingsFile`

**Files:**
- Modify: `flow/src/main/scala/orca/settings/SettingsFile.scala` (widen `SettingsError`, `AgentKey` to `private[orca]`; add `renderGlobal` + `updateGlobal`)
- Test: `flow/src/test/scala/orca/settings/SettingsFileWriteTest.scala` (next to existing settings tests — find them via `grep -rl SettingsFile flow/src/test`)

**Interfaces:**
```scala
// in object SettingsFile (both private[orca]):
/** Fresh global file: wizard header comment + one `key = value` line per set
  * role. NOT SettingsFile.Header (that wording is stack-discovery-specific). */
def renderGlobal(agents: AgentSettings): String
/** Surgical update: replace existing agent-key lines in place (first-`=`
  * split, same rule as the parser), append missing ones at the end; comments
  * and blank lines untouched. Precondition: `content` parses cleanly. */
def updateGlobal(content: String, agents: AgentSettings): String
```
`AgentSettings` already exists (per-role `Option[AgentSpec]`); render values via `AgentSpec.harnessNameFor(spec.backend)` + `spec.model.fold("")(":" + _)`.
- [ ] **Step 1: Failing tests:** round-trip `parse(renderGlobal(a), UserGlobal).agents == a` (scalacheck over the 5 backends × optional model); `updateGlobal` preserves a leading comment + blank lines, replaces a changed role in place, appends a newly-set role, leaves an unset role's existing line alone; result re-parses cleanly.
- [ ] **Step 2:** `sbt flow/test` → FAIL, implement, → PASS.
- [ ] **Step 3:** Commit: `Settings: global-file render and surgical update for the shell wizard`.

### Task 2.2: Shared PATH probe

**Files:**
- Create: `tools/src/main/scala/orca/subprocess/PathProbe.scala`
- Modify: `runner/src/main/scala/orca/runner/StackDiscovery.scala:167-187` (delegate its `command -v` shape to the helper)
- Test: `tools/src/test/scala/orca/subprocess/PathProbeTest.scala`

**Interfaces:**
```scala
private[orca] object PathProbe:
  /** `bash -c 'command -v -- "$1"' bash <word>` — word travels as an argument,
    * never interpolated. Exit 0 ⇒ resolvable. */
  def resolves(word: String, cwd: os.Path): Boolean
```
- [ ] **Step 1: Failing test:** `resolves("bash", os.pwd)` true; `resolves("definitely-not-a-binary-xyz", os.pwd)` false; a word with spaces/quotes is not interpolated (probe `"echo; touch /tmp/pwned"` → false, file absent).
- [ ] **Step 2:** Implement; refactor `StackDiscovery.unresolvedReason` to call it (behavior identical — existing `StackDiscoveryTest` must stay green).
- [ ] **Step 3:** `sbt tools/test runner/test` → PASS. Commit: `Tools: extract PathProbe; StackDiscovery delegates`.

### Task 2.3: Wizard

**Files:**
- Create: `shell/src/main/scala/orca/shell/wizard/Wizard.scala`
- Create: `shell/src/main/scala/orca/shell/wizard/FirstRun.scala`
- Test: `shell/src/test/scala/orca/shell/wizard/FirstRunTest.scala`, `WizardTest.scala`

**Interfaces:**
```scala
object FirstRun:
  /** ADR §4: file absent OR parses cleanly with all three roles None.
    * Malformed file is NOT first-run — surfaced as Left(error) for the repair
    * offer. */
  def check(globalSettingsPath: os.Path): Either[String, Boolean]

class Wizard(ui: ShellUi, probe: String => Boolean, globalSettingsPath: os.Path):
  /** Detection (BackendTag order) → three role selects (preselect: current
    * value on re-configure, else first detected, else claude) → write
    * (renderGlobal when absent, updateGlobal when present; model pin kept when
    * harness unchanged). Returns false on Cancelled (nothing written). */
  def run(reconfigure: Boolean): Boolean
```
- [ ] **Step 1: Failing tests.** `FirstRun.check`: absent → `Right(true)`; comments-only file → `Right(true)`; one role set → `Right(false)`; malformed → `Left(msg)`. `Wizard.run` with a scripted `ShellUi` stub (queue of outcomes) + `probe = Set("claude","gemini")` + temp dir: writes explicit `= <harness>` lines for all three roles; re-configure keeps an existing `codingAgent = claude:sonnet` pin when claude re-chosen, drops it when harness changed; `Cancelled` mid-wizard writes nothing; choices ordered `claude, codex, opencode, pi, gemini` (assert on the `Choice` labels passed to the stub).
- [ ] **Step 2:** `sbt shell/test` → FAIL, implement (harness list from `BackendTag.values` + `AgentSpec.harnessNameFor`; closing note lines: hand-edit hint + ADR 0020 §8 fresh-session note), → PASS.
- [ ] **Step 3:** Wire into `Main`: run wizard when `FirstRun.check` says so (before first menu), and from the Reconfigure menu item; `Left(error)` → show error + confirm "rewrite from scratch?". Commit: `Shell: welcome wizard writing global role settings`.

**Epic 2 review gate:** epic-2 roster on the diff since epic 1.

---

## Epic 3 — Flow discovery, view & edit (ADR §5–§7)

### Task 3.1: Description extractor

**Files:**
- Create: `shell/src/main/scala/orca/shell/flows/FlowDescription.scala`
- Test: `shell/src/test/scala/orca/shell/flows/FlowDescriptionTest.scala`

**Interfaces:** `object FlowDescription { def extract(lines: IterableOnce[String]): Option[String] }`

- [ ] **Step 1: Failing tests** (rule: first non-empty line in the leading blank/`//`-comment/`//>`-directive block that is `//` but not `//>`, marker+whitespace stripped; stop at first other line):
```scala
test("line-1 comment before directives"):
  assertEquals(FlowDescription.extract(List("// Do the thing.", "//> using scala 3.9.0", "val x = 1")), Some("Do the thing."))
test("comment after directives still found"):
  assertEquals(FlowDescription.extract(List("//> using scala 3.9.0", "", "// Later.", "code")), Some("Later."))
test("directive is not a description"):
  assertEquals(FlowDescription.extract(List("//> using scala 3.9.0", "val x = 1")), None)
test("block comment terminates the scan"):
  assertEquals(FlowDescription.extract(List("/** doc */", "// not reached")), None)
```
- [ ] **Step 2:** FAIL → implement (pure, no IO; caller passes `os.read.lines.stream(...).take(50)`) → PASS. Commit: `Shell: flow description extraction`.

### Task 3.2: `OrcaDir.flowsPath` + `FlowCatalog`

**Files:**
- Modify: `tools/src/main/scala/orca/OrcaDir.scala` (add `flowsPath`/`ensureFlows`, cache-subdir helpers used later: `cacheRunsPath`, mirror `ensureCache` shape)
- Create: `shell/src/main/scala/orca/shell/flows/FlowCatalog.scala`
- Test: `shell/src/test/scala/orca/shell/flows/FlowCatalogTest.scala`, extend `tools`' OrcaDir test if one exists (grep)

**Interfaces:**
```scala
// OrcaDir additions (private[orca], symlink-guarded like ensureCache):
def flowsPath(workDir: os.Path): os.Path      // <workDir>/.orca/flows
def ensureFlows(workDir: os.Path): os.Path

// shell:
enum FlowOrigin { case Project, Global, BuiltIn }
case class DiscoveredFlow(name: String, description: Option[String],
                          origin: FlowOrigin, path: os.Path,
                          shadows: List[FlowOrigin])
object FlowCatalog:
  /** Precedence Project > Global > BuiltIn keyed by filename; one entry per
    * name, winner's tier, losers recorded in `shadows`. Sorted by name.
    * `builtIns` is the extracted cache dir (task 3.4) — may not exist yet. */
  def list(projectFlows: os.Path, globalFlows: os.Path, builtIns: os.Path): List[DiscoveredFlow]
```
- [ ] **Step 1: Failing tests:** temp dirs seeded with `.sc` files (one name in all three tiers, one global-only, one built-in-only, a non-`.sc` file ignored) → precedence, shadows list, descriptions read via `FlowDescription`, missing dirs tolerated.
- [ ] **Step 2:** FAIL → implement → PASS. `sbt tools/test shell/test`. Commit: `Shell: three-tier flow catalog; OrcaDir flows dir`.

### Task 3.3: Move `examples/*.sc` → `flows/` with descriptions

**Files:**
- Move (git mv): `examples/{implement,implement-interactive,implement-enhanced,epic,issue-pr,issue-pr-bugfix}.sc` → `flows/`
- Modify: each moved file — insert the research-05 §4 description as line 1 (e.g. `implement.sc`: `// Plan a prompt into tasks and implement each with a review-and-fix loop.`; full table in `docs/research/shell/05-flow-discovery.md`)
- Modify: `examples/runnable/01-simple/create-test-project.sh`, `examples/runnable/02-interactive/create-test-project.sh` (`FLOWS_DIR="$SCRIPT_DIR/../../.."` → point at `flows/` — read the scripts first), `examples/runnable/README.md` links
- Modify: `build.sbt` `updateDocs` — add `file("flows")` to the `UpdateScalaCliVersionInDocs` file list
- Modify: `README.md` — replace `examples/` path references with `flows/`
- Check: `runner/src/test/scala/flowtests/FlowCompilesTest.scala` — if it references `examples/` paths, update

- [ ] **Step 1:** `git mv`, insert description lines, update references (grep the repo for `examples/` to catch all: `grep -rn "examples/" README.md AGENTS.md build.sbt examples/runnable runner/src`).
- [ ] **Step 2:** `sbt test` (full) → PASS; `bash examples/runnable/01-simple/create-test-project.sh --help` still resolves its flow path (dry inspection is fine — the script copies files, no agents run).
- [ ] **Step 3:** Commit: `Flows: move built-in flows examples/ -> flows/, add description lines`.

### Task 3.4: Built-in flow bundling & extraction

**Files:**
- Modify: `build.sbt` (`shell` gains a `Compile / resourceGenerators` task copying `flows/*.sc` to `orca/shell/flows/` + writing `orca/shell/flows/index` = newline-separated filenames)
- Create: `shell/src/main/scala/orca/shell/flows/BuiltInFlows.scala`
- Test: `shell/src/test/scala/orca/shell/flows/BuiltInFlowsTest.scala`

**Interfaces:**
```scala
object BuiltInFlows:
  /** Extract-if-absent to $XDG_CACHE_HOME/orca/shell/<version>/flows (default
    * ~/.cache/...), keyed by ShellVersion.value; a non-release version always
    * re-extracts and rewrites each flow's orca pin to the running version +
    * injects `//> using repository ivy2Local` (ADR §7). Returns the dir. */
  def extracted(env: String => Option[String], home: os.Path, version: String): os.Path
```
- [ ] **Step 1: Failing tests:** resources present on test classpath (the generator ran): index lists the six names, each resource readable; `extracted` into a temp `XDG_CACHE_HOME` creates files once (second call no-op — assert mtimes stable) for a release-looking version; for `"dev"` rewrites `using dep "org.virtuslab::orca:0.2.0"` → running version and injects the ivy2Local line.
- [ ] **Step 2:** FAIL → implement (resource read via `getClass.getResourceAsStream`; XDG resolution mirroring `GlobalSettings.path`'s env handling) → PASS. Commit: `Shell: bundle built-in flows as resources, extract to XDG cache`.

### Task 3.5: View & edit

**Files:**
- Create: `shell/src/main/resources/orca/shell/scala.nanorc` (own-written ~20 lines: `syntax scala`, keyword/type/string/char/number/`//` comment/`//>` directive rules — GPL nanorc collections must NOT be copied)
- Create: `shell/src/main/scala/orca/shell/flows/FlowViewer.scala`
- Create: `shell/src/main/scala/orca/shell/flows/FlowEditor.scala`
- Test: `shell/src/test/scala/orca/shell/flows/FlowViewerTest.scala`, `FlowEditorTest.scala`

**Interfaces:**
```scala
object FlowViewer:
  /** Highlighted when `tty`, plain text otherwise. Uses
    * org.jline.builtins.SyntaxHighlighter.build("classpath:/orca/shell/scala.nanorc", "scala"). */
  def render(source: String, tty: Boolean): String

object FlowEditor:
  /** $VISUAL > $EDITOR > "vi" (git/gh convention). */
  def resolveEditor(env: String => Option[String]): String
  /** Spawns `sh -c '<editor> "$@"' <editor> <path>` inheriting the tty, so
    * editor values with arguments work. Returns the exit code. */
  def edit(editor: String, path: os.Path): Int
  /** Built-ins are never edited in the cache: pick project/global, copy, then
    * edit the copy (which shadows the built-in). Returns the copy's path. */
  def customizeTarget(flow: DiscoveredFlow, tier: FlowOrigin, workDir: os.Path,
                      globalFlows: os.Path): os.Path
```
- [ ] **Step 1: Failing tests:** `render` non-tty is byte-identical to input; tty output contains ANSI escapes for a keyword-bearing snippet and strips to the input via `fansi.Str(..., ErrorMode.Sanitize).plainText`; `resolveEditor` precedence incl. both-set and neither-set; `customizeTarget` copies a built-in into `.orca/flows/` (created via `OrcaDir.ensureFlows`) or the global dir and refuses on name collision with an existing file (returns/throws — pick `Either[String, os.Path]` and assert the message).
- [ ] **Step 2:** FAIL → implement → PASS. `edit`'s spawn itself: manual smoke only (`EDITOR=true sbt shell/run` — editor exits immediately); the sh -c argv construction is a pure function — extract `def editArgv(editor: String, path: os.Path): Seq[String]` and test it (quoting: path with spaces survives, editor string is not escaped — it is deliberately shell-interpreted).
- [ ] **Step 3:** Wire ViewFlow/EditFlow menu items: select from `FlowCatalog.list`, view→print+redraw, edit→in place for Project/Global, customize dialog for BuiltIn. Commit: `Shell: view (nanorc highlighting) and edit (VISUAL/EDITOR) flows`.

**Epic 3 review gate:** epic-3 roster; security-reviewer looks at `editArgv`, `customizeTarget` path handling, nanorc licensing note.

---

## Epic 4 — Flow launcher (ADR §2)

### Task 4.1: Launch command construction + degradation logic

**Files:**
- Create: `shell/src/main/scala/orca/shell/run/FlowLauncher.scala`
- Test: `shell/src/test/scala/orca/shell/run/FlowLauncherTest.scala`

**Interfaces:**
```scala
object FlowLauncher:
  /** Forced mode (default): scala-cli run <path> --dep org.virtuslab::orca:<v> -- <task…>
    * Fallback mode (pin-honouring): same argv without --dep. */
  def argv(flow: os.Path, orcaVersion: Option[String], task: String, verbose: Boolean): Seq[String]
  /** ADR §2: run forced; if the child fails AND `scala-cli compile --dep …`
    * also fails (compile error, not flow error), offer pin-honouring re-run
    * with the degradation notice. */
  def run(ui: ShellUi, flow: os.Path, task: String, workDir: os.Path): LaunchResult
enum LaunchResult { case Ok; case Failed(exit: Int); case Cancelled }
```
- [ ] **Step 1: Failing tests** for `argv`: forced includes `--dep org.virtuslab::orca:0.2.0` before the flow path? (order: `scala-cli`, `run`, `--jvm`, `21`? — no: flows pin their own jvm; keep argv minimal: `Seq("scala-cli", "run", flow.toString) ++ dep ++ Seq("--", task)`; verbose adds `-v` after `--`? OrcaArgs takes `--verbose` — check `runner/src/main/scala/orca/OrcaArgs.scala` and match its flag). Non-release shell version (`ShellVersion.isRelease == false`) → `orcaVersion = None` (never force an unpublishable version).
- [ ] **Step 2:** FAIL → implement `argv` → PASS.
- [ ] **Step 3:** Implement `run`: spawn via `os.proc(argv).call(cwd = workDir, stdin = os.Inherit, stdout = os.Inherit, stderr = os.Inherit, check = false)`; on nonzero exit run `scala-cli compile <flow> --dep …` capturing stderr — nonzero there = compile failure → show notice ("this flow pins an orca version incompatible with the shell (<v>); sessions from a pin-honouring run can't be continued — run anyway?") and offer fallback re-run. Commit: `Shell: flow launcher with forced orca version and pin-honouring fallback`.

### Task 4.2: Terminal & signal supervision around the child

**Files:**
- Modify: `shell/src/main/scala/orca/shell/run/FlowLauncher.scala`
- Modify: `shell/src/main/scala/orca/shell/Main.scala` (shared child-run bracket used by launcher, editor, harness exec)
- Create: `shell/src/main/scala/orca/shell/run/ChildTerminal.scala`
- Test: `shell/src/test/scala/orca/shell/run/ChildTerminalTest.scala`

**Interfaces:**
```scala
object ChildTerminal:
  /** Runs `body` with the shell's terminal paused: JLine Terminal attributes
    * saved, restored in finally (a child crashed in raw mode must not wedge
    * the menu). While the child runs the shell ignores SIGINT (Ctrl-C reaches
    * the foreground child via the shared process group); the loop resumes
    * after the child exits. */
  def withChild[A](terminal: Terminal)(body: => A): A
```
- [ ] **Step 1: Failing test:** with a dumb/test terminal, `withChild` restores attributes after `body` throws (assert `getAttributes` equality before/after) — the JLine `Terminal` API makes this testable without a pty via `TerminalBuilder.builder().dumb(true)`.
- [ ] **Step 2:** FAIL → implement (`terminal.pause()`/`resume()` + `enterRawMode` avoidance; attributes via `getAttributes`/`setAttributes`; `sun.misc.Signal.handle` is NOT used — `os.proc(...).call` with inherited stdio already leaves Ctrl-C to the child; the shell survives because `call` returns on child death and ConsoleUI's next prompt reinstalls its own handler. Verify this empirically in step 3 and ONLY add a JVM-level `Signal.handle("INT", …)` ignore-while-child-runs if the shell dies in the smoke test.) → PASS.
- [ ] **Step 3:** Manual smoke (documented in the task commit message): `sbt shell/run` → run a trivial flow script that sleeps → Ctrl-C kills the child, menu redraws. Wire RunFlow menu item end-to-end (catalog select → task input → launcher). Commit: `Shell: terminal/signal bracket around child processes; run-flow menu item`.

### Task 4.3: `--dep`-override canary (integration)

**Files:**
- Create: `shell/src/test/scala/orca/shell/run/DepOverrideCanaryTest.scala`

- [ ] **Step 1:** Test gated like existing integration tests (`assume(sys.env.contains("ORCA_INTEGRATION"))` — grep the repo for `ORCA_INTEGRATION` to copy the exact gating idiom): write a temp script pinning `//> using dep "com.lihaoyi::os-lib:0.9.1"` that prints `os.read(…)`-independent version info — simplest: script prints `BuildInfo`? os-lib has no version API; instead assert via `scala-cli run temp.sc --dep com.lihaoyi::os-lib:0.11.3 --command`-style: run `scala-cli compile --print-class-path` variant? Use the research-verified approach: `scala-cli run --command <script> --dep com.lihaoyi::os-lib:0.11.3`, scan the printed `-cp` line for `os-lib_3-0.11.3` and absence of `0.9.1`.
- [ ] **Step 2:** `ORCA_INTEGRATION=1 sbt shell/test` → PASS locally (needs network+scala-cli). Commit: `Shell: canary test pinning scala-cli --dep-overrides-directive semantics`.

**Epic 4 review gate:** epic-4 roster; security-reviewer on argv construction and child-process handling.

---

## Epic 5 — Session event & run manifests (ADR §8, library side)

### Task 5.1: `OrcaEvent.SessionCommitted` + emissions

**Files:**
- Modify: `tools/src/main/scala/orca/events/OrcaEvent.scala`
- Modify: `tools/src/main/scala/orca/agents/BaseAgent.scala:96-110` (autonomous text), `tools/src/main/scala/orca/agents/AgentCall.scala` (structured retry ~213-227; interactive `sessions.register` ~283)
- Test: `tools/src/test/scala/...` next to existing agent/backend tests (grep for the StubBackend/StubAgent fixtures in `tools % "test->test"`)

**Interfaces:**
```scala
/** Fires when a session's first turn commits (its wire id, if any, is known).
  * `wireId` is the persistable id (`agent.resumeWireId`) — None for backends
  * whose sessions do not survive the run (pi). Once per (backend, clientId,
  * wireId) commit; listeners dedup. */
case SessionCommitted(
    backend: String,          // BackendTag.wireName
    clientId: String,         // SessionId's underlying UUID string
    wireId: Option[String],
    agent: String,            // Agent.name
    role: Option[String]      // Agent.role
)
```
- [ ] **Step 1: Failing tests** using the existing stub backend fixtures: an autonomous `run` emits exactly one `SessionCommitted` with the stub's wire id; `quietTextTurn` emits none; the structured path emits once even with a parse-retry; interactive path emits after `register`. (Collect events with a recording listener.)
- [ ] **Step 2:** FAIL → implement: emission AFTER `backend.runAutonomous`/`register` returns, reading `resumeWireId(session)` — at these three sites `name`/`role`/`events` are in scope (BaseAgent has `agentName`; DefaultAgentCall has `agentName`/`agentRole`). → PASS (`sbt tools/test`, then `sbt test` — backends' own suites must stay green).
- [ ] **Step 3:** Commit: `Events: SessionCommitted emitted at the agent layer`.

### Task 5.2: Run manifest writer

**Files:**
- Create: `runner/src/main/scala/orca/runner/RunManifest.scala` (schema + codecs)
- Create: `runner/src/main/scala/orca/runner/RunManifestWriter.scala` (listener)
- Modify: `runner/src/main/scala/orca/flow.scala` (attach beside `LoggingListener`; finalize outcome in `flow()`'s finally), `runner/src/main/scala/orca/runner/FlowLifecycle.scala` only if the stage-stack needs a hook (it shouldn't — the listener consumes `StageStarted`/`StageCompleted`)
- Modify: `tools/src/main/scala/orca/OrcaDir.scala` (`cacheRunsPath(workDir) = ensureCache(workDir)/"runs"` helper if not added in 3.2)
- Test: `runner/src/test/scala/orca/runner/RunManifestWriterTest.scala`

**Interfaces (schema v1 — research 08 §4 verbatim):**
```scala
case class ManifestSession(harness: String, wireId: Option[String], resumable: Boolean,
    reason: Option[String], agent: String, role: Option[String], stage: Option[String],
    sessionName: Option[String], kind: String, firstSeenAt: String, lastActiveAt: String)
case class RunManifest(manifestVersion: Int /* 1 */, orcaVersion: String, flow: Option[String],
    workDir: String, pid: Long, startedAt: String, finishedAt: Option[String],
    outcome: String /* running|succeeded|failed */, sessions: List[ManifestSession])

class RunManifestWriter(workDir: os.Path, clock: () => java.time.Instant) extends OrcaListener:
  /** Maintains a stage stack from Stage events; on SessionCommitted upserts by
    * (harness, wireId) — last-write-wins for stage/lastActiveAt — and rewrites
    * `.orca/cache/runs/<startedAtMillis>-<pid>.json` atomically (temp +
    * atomicMove, the ProgressStore.writeLog pattern). Prunes to the newest 20
    * files on first write. */
  def onEvent(event: OrcaEvent): Unit
  def finish(outcome: String): Unit   // sets finishedAt + outcome, final write
```
- [ ] **Step 1: Failing tests:** feed a scripted event sequence (StageStarted("plan") → SessionCommitted(claude…) → StageCompleted → StageStarted("code") → SessionCommitted(same wire id) → …): file exists after first session event with `outcome: "running"`; upsert updated `stage`/`lastActiveAt` not `firstSeenAt`; nested stages stamp the top of the stack; pi-shaped event (wireId None) → `resumable: false` with the ADR reason string; `finish("succeeded")` finalizes; 25 pre-seeded run files → pruned to 20 newest; atomic write leaves no `*.tmp` behind.
- [ ] **Step 2:** FAIL → implement (jsoniter codecs; `pid` via `ProcessHandle.current().pid()`; clock injected — no `Instant.now()` in logic) → PASS.
- [ ] **Step 3:** Wire into `runFlow` (always-attached like `LoggingListener`) and `flow()` (`finally`: `writer.finish(if failed then "failed" else "succeeded")`). `sbt runner/test` then full `sbt test`. Commit: `Runner: per-run session manifests under .orca/cache/runs/`.

**Epic 5 review gate:** epic-5 roster. This epic changes the published library — scala-fp-reviewer + test-reviewer are mandatory.

---

## Epic 6 — Continue-session menu (ADR §8, shell side)

### Task 6.1: Manifest reading + session listing

**Files:**
- Create: `shell/src/main/scala/orca/shell/sessions/ManifestReader.scala`
- Test: `shell/src/test/scala/orca/shell/sessions/ManifestReaderTest.scala`

**Interfaces:**
```scala
object ManifestReader:
  /** Newest-first by startedAt. Skips files with manifestVersion > 1 (with a
    * notice line collected into `warnings`); a manifest with outcome "running"
    * whose pid is dead counts as crashed — sessions still offered. Absent/empty
    * dir → Nil (menu item disabled with the ADR hint). */
  def list(workDir: os.Path, pidAlive: Long => Boolean): (List[RunManifest], List[String])
```
- [ ] **Step 1: Failing tests:** temp `.orca/cache/runs/` with three manifests (one v2 → skipped+warning; one running+dead pid → included; ordering) — reuse `RunManifest` codecs from runner (shell depends on runner: import, don't duplicate).
- [ ] **Step 2:** FAIL → implement → PASS. Commit: `Shell: run-manifest reader`.

### Task 6.2: Resume commands + menu wiring

**Files:**
- Create: `shell/src/main/scala/orca/shell/sessions/ResumeCommand.scala`
- Modify: `shell/src/main/scala/orca/shell/Main.scala`
- Test: `shell/src/test/scala/orca/shell/sessions/ResumeCommandTest.scala`

**Interfaces:**
```scala
object ResumeCommand:
  /** ADR §8 table. Left(reason) = not resumable (pi always; any wireId-less
    * session; gemini when the id is not in `gemini --list-sessions` output —
    * that lookup is performed by the caller and passed as `geminiIndex`). */
  def build(s: ManifestSession, geminiIndex: Option[Int]): Either[String, Seq[String]]
  /** Parses `gemini --list-sessions` stdout to find the 1-based index of a
    * session UUID; format per research 08 (verify against the installed CLI
    * during implementation and pin the parse with a fixture). */
  def geminiIndexOf(listOutput: String, uuid: String): Option[Int]
```
- [ ] **Step 1: Failing tests:** claude → `Seq("claude","--resume",uuid)`; codex → `Seq("codex","resume",id)`; opencode → `Seq("opencode","--session",id)`; gemini with `geminiIndex=Some(3)` → `Seq("gemini","--resume","3")`, with None → Left(reason); pi → Left(the manifest's stored reason); `geminiIndexOf` against a captured fixture of `--list-sessions` output.
- [ ] **Step 2:** FAIL → implement → PASS.
- [ ] **Step 3:** Wire ContinueSession: rows labeled `<agent> (<role>) — stage <stage> [<harness>]` newest run first; exec via the ChildTerminal bracket from the manifest's `workDir`; menu item disabled with the ADR hint when `list` is empty. Commit: `Shell: continue-session menu resuming harness UIs`.

**Epic 6 review gate:** epic-6 roster.

---

## Epic 7 — Create-a-flow, packaging, install & docs (ADR §1, §9)

### Task 7.1: Create-a-flow menu item

**Files:**
- Create: `shell/src/main/scala/orca/shell/create/CreateFlow.scala`
- Modify: `build.sbt` (shell resourceGenerators also bundles `README.md` + `flows/implement.sc` + `flows/implement-interactive.sc` under `orca/shell/api/`)
- Test: `shell/src/test/scala/orca/shell/create/CreateFlowTest.scala`

**Interfaces:**
```scala
object CreateFlow:
  /** ADR §9. Extracts README + the two example flows into the harness's
    * workspace: project → {workDir}/.orca/cache/orca-api-<version>/ (via
    * OrcaDir.ensureCache); global → ~/.config/orca/cache/orca-api-<version>/
    * and the harness launches with cwd ~/.config/orca. */
  def extractApiMaterial(target: os.Path, version: String): os.Path
  /** The initial prompt: goal, target path, verbatim `//> using` header with
    * the shell's orca version, line-1 // description convention, pointers to
    * the extracted README/examples, `scala-cli compile` check, and the
    * "fork/ordering rules are runtime-enforced" caveat. Tag-pinned raw README
    * URL as last-resort line. */
  def initialPrompt(goal: String, targetPath: os.Path, apiDir: os.Path, orcaVersion: String): String
  /** Harness argv for an interactive session opened with a prompt: claude
    * <prompt>; codex <prompt>; opencode (prompt via TUI paste — research 07;
    * verify each CLI's positional-prompt support during implementation and
    * record in a comment). */
  def harnessArgv(backend: BackendTag, prompt: String): Seq[String]
```
- [ ] **Step 1: Failing tests:** `extractApiMaterial` writes the three files (assert contents match resources) idempotently; `initialPrompt` contains the orca version, the target path, the compile-check line and the runtime-rules caveat (substring asserts — the prompt text is the deliverable, keep it in one place).
- [ ] **Step 2:** FAIL → implement → PASS.
- [ ] **Step 3:** Wire CreateFlow menu item (target-tier select → goal input → harness select defaulting to configured codingAgent → extract → exec harness via ChildTerminal from the right cwd). Commit: `Shell: create-a-flow via harness with bundled API material`.

### Task 7.2: install.sh + README + final verification

**Files:**
- Create: `install.sh` (repo root)
- Modify: `README.md` (new "Orca Shell" section: what it is, `curl | bash` install, the pinned one-liner alternative), `build.sbt` (`updateDocs`: ensure the README one-liner's coordinate is rewritten — it is, `UpdateVersionInDocs` covers README)
- Modify: `AGENTS.md` if it documents module layout (add `shell`)

- [ ] **Step 1:** `install.sh`: POSIX sh, downloads nothing — writes the shim to `~/.local/bin/orca` (heredoc with `latest.release`), `chmod +x`, PATH hint. Shim body exactly as ADR §1.
- [ ] **Step 2:** `bash install.sh` into a temp `HOME` (manual check) → shim exists, is executable, `grep latest.release` passes. `shellcheck install.sh` if available.
- [ ] **Step 3:** Full `sbt test` + `sbt scalafmtCheckAll` (if the build enforces fmt — check `commonSmlBuildSettings`); end-to-end smoke: `sbt shell/publishLocal` then run the shim against ivy2Local (dev-version path) in a scratch git repo — wizard, list flows, view one, run a stub flow.
- [ ] **Step 4:** Commit: `Shell: install script and README section`.

**Epic 7 review gate:** epic-7 roster; then a final whole-branch `superpowers:requesting-code-review` pass before PR.

---

## Self-review (done at planning time)

- ADR §1–§9 each map to ≥1 task (§1→1.1/7.2, §2→4.x, §3→1.2/1.3, §4→2.x, §5→3.1–3.3, §6→3.5, §7→3.4, §8→5.x/6.x, §9→7.1). Non-goals introduce no tasks.
- Known deliberate deferrals inside tasks: signal-handling in 4.2 is verify-then-harden (research says inherited stdio suffices; the step says to test and only add `Signal.handle` if the smoke test fails); `harnessArgv` prompt-passing per CLI is verify-during-implementation with the research pointer.
- Type consistency: `ShellUi`/`Choice`/`UiOutcome` (1.2) consumed by 2.3, 3.5, 4.1, 6.2, 7.1; `DiscoveredFlow`/`FlowOrigin` (3.2) by 3.5, 4.x; `ShellVersion` (1.1) by 3.4, 4.1, 7.1; `RunManifest`/`ManifestSession` (5.2) by 6.1/6.2 — names match as written.
