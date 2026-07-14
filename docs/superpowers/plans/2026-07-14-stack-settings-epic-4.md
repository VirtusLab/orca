# Epic 4: Agent-Based Discovery + Write Sequencing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans.
> Spec: `adr/0019-project-stack-settings.md` § Auto-discovery (the whole
> section — prompt principles, mechanical checks, failure semantics,
> sequencing). Landed API this builds on (verify, don't re-derive):
> `FlowLifecycle.setup(args, agent, git, workDir, branchNaming,
> settingsOverride, store, emit): FlowSetup` with
> `FlowSetup(store, featureBranch, startBranch, stackSettings)`; private
> `resolveStackSettings(workDir, settingsOverride)` running BEFORE
> `snapshotLog`/`ensureClean` (verify its exact post-review shape — the
> Epic 3 review batch removes the `settingsFileExisted` boolean); `ctx.populateStackSettings` called from
> `FlowLifecycle.run` after `surfaced(setup(...))`;
> `SettingsFile.render(entries)` byte-exact contract (own-line `#` evidence
> comments above each `key = command`; `# key =   (reason)` unset lines;
> header two lines; trailing newline); `OrcaDir.settingsPath`;
> `StackSettings`; setup already mints `InStage` and runs the lead agent
> for branch naming (the precedent discovery reuses).

**Goal:** missing settings file (no override) → one read-only cheap-tier
agent run proposes commands with evidence; two orca-side mechanical checks
demote unresolvable ones; the file is written untracked in the
post-`ensureClean` / pre-header-commit slot (resume arm: same slot, no
commit); reported as flow events; discovery failure aborts as a surfaced
setup failure — never degraded to a written empty file.

## Global constraints

- NO real stack→command examples in the prompt (ADR records the rejection;
  one fictional-stack example only).
- Discovery runs ONLY when: no override AND file absent at the
  pre-`ensureClean` read. Both conditions live in `resolveStackSettings`'s
  needs-discovery marker (Task 4) — an override means the file is neither
  read nor written, and a file the stash swept must not look absent, so
  the pre-stash read is the single authority. (Epic 3's review dropped the
  separate `settingsFileExisted` field; the marker replaces it.)
- Zero warnings; per-task commits; scalafmtAll; present-tense comments.

---

### Task 1: `SettingsEntry.Demoted` (flow module)

**Files:** `flow/src/main/scala/orca/settings/SettingsEntry.scala`,
`SettingsFile.scala` (renderEntry), `SettingsFileTest.scala`.

New case: `Demoted(key: String, command: String, reason: String)` rendering
`# <key> = <command>   (<reason>)` — a commented-out command line carrying
why it was demoted; invisible to `parse` like every `#` line. Reason and
command sanitized like `Unset.reason` (collapse whitespace runs).

- [ ] Failing render + round-trip-invisibility test → implement → PASS →
      commit: `git commit -m "SettingsEntry.Demoted: commented-out command with reason"`

### Task 2: discovery result types + prompt resource + entry assembly

**Files:**
- Create: `runner/src/main/scala/orca/runner/StackDiscovery.scala`
- Create: the prompt as a resource following the existing
  `orca.util.PromptResource` pattern (find how `ReviewLoopPrompts` loads
  `summarise-lint.md` and mirror; name: `stack-discovery.md`)
- Test: `runner/src/test/scala/orca/runner/StackDiscoveryTest.scala`

**Result types** (`private[runner]`, jsoniter/schema-friendly — mirror how
existing `resultAs` payloads like `ReviewResult` declare codecs/schemas):
```scala
case class DiscoveredCommand(
    command: String,
    evidencePath: String,        // repo-relative file that justifies it
    evidenceNote: Option[String] // key/line/why — free text
)
case class DiscoveredTask(
    commands: List[DiscoveredCommand] = Nil,
    unsetReason: Option[String] = None
)
case class StackDiscoveryResult(
    format: DiscoveredTask,
    lint: DiscoveredTask,
    test: DiscoveredTask
)
```

**Prompt** (resource file; the ADR's principles verbatim where possible):
```
You are inspecting a source repository (read-only) to discover how this
project formats, lints, and tests itself. Your output configures automated
gates that run repeatedly, so a wrong command is worse than no command:
propose a command ONLY if files in this repository justify it.

Definitions:
- format: rewrites source files to the project's canonical style.
- lint: a cheap sanity gate that a change is well-formed — typically a
  compile or typecheck that covers test sources WITHOUT executing any
  tests. It must be substantially faster than the test suite. Never
  propose the test runner (or a command that runs tests) as lint.
- test: runs the project's test suite.

Procedure:
1. Survey the tree for build definitions, lockfiles, task runners, tool
   configs, and CI workflows (CI shows which commands the project itself
   trusts).
2. Prefer the project's own entry points over reconstructing them: a
   justfile/Makefile recipe, a package.json/composer.json script, a build
   wrapper (./gradlew, ./mill). Emit the entry point (e.g. `just fmt`),
   not the commands it happens to run; with a wrapper present, never emit
   the bare tool.
3. Before proposing any tool, verify it is set up HERE: its config file,
   plugin/dependency declaration, or script entry must be present. A tool
   being conventional for this ecosystem is NOT evidence.
4. For every command, cite the repo-relative file that justifies it
   (evidencePath) and optionally the key/task/line (evidenceNote). If you
   cannot cite a file, do not propose the command — leave the task unset
   with a one-line reason. An unset task with an accurate reason is a
   correct, complete answer; never guess to fill a slot.
5. A repo with several stacks (e.g. a Rust core and a JS frontend)
   contributes each stack's commands to each task.
6. Ignore orca flow scripts (.sc files depending on the `orca` library) —
   they drive this automation and are not part of the project's stack.

Never propose a command because it is the usual one for a build tool you
recognized. Every command must be traceable to this repository's files.

The example below uses a FICTIONAL build tool, only to show the output
shape and the lint-vs-test distinction — derive real values from the
repository:

{"format": {"commands": [{"command": "acme style --write",
    "evidencePath": "style.acme",
    "evidenceNote": "also run by CI in .ci/check.yml line 12"}]},
 "lint":   {"commands": [{"command": "acme compile --include-tests",
    "evidencePath": "acme.build",
    "evidenceNote": "compiles main and test sources, executes nothing"}]},
 "test":   {"unsetReason": "no test directory or CI test step found"}}
```

**Assembly** (pure, unit-tested): `StackDiscovery.toEntries(result,
checks): (List[SettingsEntry], StackSettings)` — per task, per command:
passing both checks → `Command(key, command, comment = evidencePath +
optional "; " + note)` and the command joins the returned `StackSettings`;
failing a check → `Demoted(key, command, reason)`; task with no surviving
commands and an `unsetReason` → `Unset(key, reason)`; task with neither →
`Unset(key, "no evidence found")`. Checks are injected as functions
(`command => Option[String]` demotion-reason, `path => Boolean`) so
assembly tests need no processes.

- [ ] Failing assembly tests (survive/demote/unset arms, comment shape) →
      implement types + prompt resource + assembly → PASS → commit:
      `git commit -m "StackDiscovery: result types, prompt, entry assembly"`

### Task 3: mechanical checks

**Files:** `StackDiscovery.scala` + `StackDiscoveryTest.scala`.

```scala
/** None = resolvable; Some(reason) = demote. Strips leading VAR=
  * assignment tokens, then resolves the first word through the execution
  * environment's own lookup (builtins included). Words are passed as
  * arguments — never interpolated into the bash script text. */
private[runner] def commandUnresolvable(command: String, workDir: os.Path): Option[String]
```
Implementation: tokenize on whitespace; drop leading `NAME=...` tokens
(regex `^[A-Za-z_][A-Za-z0-9_]*=`); first remaining token is the word;
empty → demote ("empty command"). Resolution:
`os.proc("bash", "-c", "command -v -- \"$1\"", "bash", word).call(cwd = workDir, check = false)`
— exit 0 resolvable; else if the word contains `/` and
`os.exists(workDir / os.RelPath(word))` with the executable bit (or is a
path outside workDir → just demote), resolvable; else demote
(`"<word>: not found on PATH"`). Evidence check:
`evidenceExists(path: String, workDir: os.Path): Boolean` — reject
absolute paths and `..` traversal (construct `os.SubPath` and catch), then
`os.exists(workDir / sub)`; failure demotes with
`"evidence file <path> not found"`.

- [ ] Failing tests: plain word on PATH; VAR= prefix stripped; two VAR=
      prefixes; word with shell metacharacters (`;`, spaces via quotes) is
      NOT interpreted (the `"$1"` argv trick — assert e.g. `"echo; touch marker"`'s
      first word `echo;`... actually assert no marker file is created and
      the result is a demotion or resolution WITHOUT side effects);
      `./script.sh` present+executable resolves; absent demotes; evidence
      relative exists/missing; evidence `../escape` and `/abs` rejected.
- [ ] Implement → PASS → commit:
      `git commit -m "StackDiscovery mechanical checks: PATH resolution, evidence existence"`

### Task 4: lifecycle integration

**Files:** `runner/src/main/scala/orca/runner/FlowLifecycle.scala`,
`StackDiscovery.scala` (an orchestrating `discover(agent, workDir, emit)`
returning `(StackSettings, List[SettingsEntry])` or throwing).

Restructure `setup`:
- `resolveStackSettings` keeps deciding pre-`ensureClean`; when no
  override and no file it now returns a needs-discovery marker (private
  enum or Option) instead of `StackSettings.empty`.
- AFTER `ensureClean` (and the log snapshot/restore), when discovery is
  needed — on BOTH the fresh and resume arms, before `freshRun`'s header
  commit on the fresh arm: run
  `agent.cheap.withReadOnly.resultAs[StackDiscoveryResult]` (verify the
  accessor spelling/order against `flow/src/main/scala/orca/accessors.scala`
  and how `Plan.autonomous.from` invokes agents; `InStage` is already
  given in setup), `emitPrompt = false` if that's the house style for
  internal calls (mirror `lint`'s summarise call).
- Apply checks → assemble entries → `os.write` the rendered file to
  `OrcaDir.settingsPath(workDir)` (plain write; the file must be untracked
  — never `git add`) → emit events: one Step "no .orca/settings.properties
  — running stack discovery" BEFORE the agent run; after: one Step per
  written line-pair (`format = cargo fmt` + its evidence) or demotion, and
  a WARNING Step when a task ends up with no commands ("stack settings:
  no <task> command — gate disabled"); final Step "written to
  .orca/settings.properties — review and edit as needed."
- The fresh arm's header commit (`freshRun` → forceAdd(store.path) +
  `git.commit`, whose add -A sweeps the whole tree) — VERIFY the settings
  file lands in that commit (integration test asserts it); resume arm: no
  commit, file stays untracked.
- Failure: NO catch around the agent run — exceptions propagate; `run`'s
  existing `surfaced(...)` bracket reports them. Add a comment stating the
  ADR rationale (no degrade-to-empty-file: frozen semantics would make a
  transient outage permanent).
- `FlowSetup.stackSettings` carries the discovered settings; populate
  unchanged.

- [ ] Failing lifecycle test (canned agent — see Task 5's stub, build it
      first if needed): fresh arm discovers + writes + header commit
      contains the file → implement → PASS → commit:
      `git commit -m "Lifecycle runs stack discovery post-ensureClean, writes settings pre-header-commit"`

### Task 5: test seam + fixture migration

**Files:** new `runner/src/test/scala/orca/runner/CannedDiscoveryAgent.scala`
(or inline in the lifecycle test file if that's the local idiom); every
runner test that drives `setup`/`runFlow` against a repo with NO settings
file and NO override — they now hit discovery against `StubAgent`, whose
`resultAs` throws.

Convention (apply uniformly): the shared test-run helpers default to
passing `stackSettings = Some(StackSettings.empty)`; tests exercising
discovery explicitly pass `None` + the canned agent. Sweep
`FlowLifecycleTest`, `OrcaTest`, `OrcaOverridesTest`, `OpencodeFlowTest`,
and any other full-flow suites the compiler/tests flag. The canned agent
mirrors the existing stub idiom (find how `StubAgent` fakes `resultAs`
and return a fixed `StackDiscoveryResult` instead of throwing).

- [ ] Migrate; full `runner/test` green → commit:
      `git commit -m "Discovery test seam: canned agent, settings default in flow fixtures"`

### Task 6: integration tests (sequencing arms)

**Files:** `FlowLifecycleTest.scala` (+ helpers).

- [ ] Fresh run, no file: file exists after, content matches canned result
      (byte-exact render), IS in the branch's first commit, events include
      the running/written Steps.
- [ ] Demotion arm: canned result with an unresolvable command → file has
      `# lint = ...` demoted line + gate-disabled warning Step; parse of
      written file yields only surviving commands.
- [ ] Resume arm (existing progress log, settings file deleted): discovery
      runs, file written, NOT committed (untracked after setup).
- [ ] Legacy-ignored repo (`.orca/` in .gitignore): file written, stays
      untracked, no crash (and the Epic-1 ignored-warning fires).
- [ ] Failure arm: canned agent that throws → setup aborts (SurfacedFlowFailure
      out of run), NO settings file written, no stage ran.
- [ ] All-unset arm: canned all-unset result → file written all-commented,
      warning Steps, `StackSettings.empty` populated.
- [ ] Commit: `git commit -m "Discovery sequencing integration tests"`

### Task 7: full-build gate

- [ ] Full `sbt --client test` green, zero warnings, status clean; report
      SHAs, deviations, and the exact discovery event lines (Epic 5's docs
      quote them).

## Self-review notes

ADR § Auto-discovery coverage: principle prompt + fictional example (T2),
evidence citations in file comments (T2 assembly), two mechanical checks +
demotion-with-reason (T1/T3), events (T4), abort-on-failure with recorded
rationale (T4), write sequencing fresh/resume/legacy arms + frozen-file
semantics (T4/T6), no-real-catalog constraint (T2 prompt). The
`settingsFileExisted` authority from Epic 3 gates discovery (T4).
