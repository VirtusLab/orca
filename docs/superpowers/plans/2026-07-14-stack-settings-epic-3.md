# Epic 3: Context Plumbing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans.
> Spec: `adr/0019-project-stack-settings.md` (§ API, § Auto-discovery
> sequencing paragraph — only its *read* half; discovery itself is Epic 4).
> Landed so far: `orca.OrcaDir` (incl. `settingsPath`/`settingsSubPath`),
> `FlowContext.workDir`, `StackSettings` (+ `empty`), `SettingsFile.parse`
> (`Either[String, StackSettings]`), `SettingsFile.render`.

**Goal:** settings resolve end-to-end with the interim chain
**override > file > empty** — populated during lifecycle setup, frozen on
the context. Epic 4 replaces only the "absent file" arm with discovery.

**Architecture:** the resolved value travels `flow(stackSettings = ...)` →
`runFlow` → `FlowLifecycle.run` → `setup` (which reads the file pre-
`ensureClean`) → back in `FlowSetup` → `run` populates a one-shot slot on
`DefaultFlowContext`. Construction stays pure (slot, not constructor
value) because the context is built before setup runs.

## Global constraints

- Malformed settings file must abort BEFORE `ensureClean` (no stash, no
  branch mutation) with an `OrcaFlowException` whose message includes the
  parser's error (which already names valid keys).
- Zero warnings; per-task commits; scalafmtAll; present-tense comments.

---

### Task 1: `FlowContext.stackSettings` + population slot

**Files:**
- Modify: `flow/src/main/scala/orca/FlowContext.scala` — add
  `def stackSettings: StackSettings` (scaladoc: resolved once during
  lifecycle setup — override > `.orca/settings.properties` > empty — and
  frozen for the run; ADR 0019).
- Modify: `runner/src/main/scala/orca/runner/DefaultFlowContext.scala` —
  private `AtomicReference[Option[StackSettings]](None)`;
  `private[orca] def populateStackSettings(s: StackSettings): Unit` (second
  call throws `IllegalStateException` — the lifecycle populates exactly
  once); `def stackSettings` throws `IllegalStateException`
  ("stackSettings read before lifecycle setup") when unpopulated —
  unreachable in production (setup runs before the body), and a loud
  pointer for test doubles.
- Modify: `flow/src/test/scala/orca/TestFlowContext.scala` (+
  `TestFlowControl`, and any other implementor the compiler flags — Epic 1
  already touched the full implementor list for `workDir`; mirror it) —
  constructor param `stackSettings: StackSettings = StackSettings.empty`.
- Test: `runner/src/test/scala/orca/runner/DefaultFlowContextTest.scala` —
  one test: populate-then-read returns the value; double-populate throws;
  read-before-populate throws.

**Interfaces (produces):** `FlowContext.stackSettings: StackSettings`;
`DefaultFlowContext.populateStackSettings` (consumed by Task 3 and Epic 4).

- [ ] **Step 1:** failing `DefaultFlowContextTest` cases.
- [ ] **Step 2:** implement; `sbt --client "flow/Test/compile runner/test tools/Test/compile"` green.
- [ ] **Step 3:** format + commit:
      `git commit -m "FlowContext.stackSettings with one-shot lifecycle population"`

### Task 2: `flow(stackSettings = ...)` parameter

**Files:**
- Modify: `runner/src/main/scala/orca/flow.scala` — `flow(...)` and
  `runFlow(...)` gain `stackSettings: Option[StackSettings] = None`
  (placed next to `branchNaming`; scaladoc: explicit settings win — the
  file is neither read nor written; the language-specific-flow escape
  hatch). Thread to `FlowLifecycle.run` as a plain parameter (NOT via
  `FlowWiring` — wiring is the tool/agent-factory bag consumed at context
  construction; settings are lifecycle input, like `branchNaming`).
- Modify: `runner/src/main/scala/orca/runner/FlowLifecycle.scala` —
  `run(...)` gains `stackSettings: Option[StackSettings]`.
- Test: `runner/src/test/scala/flowtests/FlowCompilesTest.scala` — canary:
  a `flow(..., stackSettings = Some(StackSettings(format = List("cargo fmt"))))`
  call shape typechecks.

- [ ] **Step 1:** canary first (Test/compile FAILs), then thread the
      parameter until green (`runner/Test/compile`).
- [ ] **Step 2:** format + commit:
      `git commit -m "flow(stackSettings = ...) override parameter"`

### Task 3: setup reads the file, run populates the slot

**Files:**
- Modify: `runner/src/main/scala/orca/runner/FlowLifecycle.scala`:
  - `setup(...)` gains `workDir: os.Path` and
    `settingsOverride: Option[StackSettings]`; `FlowSetup` gains
    `stackSettings: StackSettings` and
    `settingsFileExisted: Boolean` (Epic 4's discovery decision reads this
    — the pre-`ensureClean` read is authoritative; a file the stash later
    sweeps must NOT look absent).
  - Resolution, placed immediately after `warnIfSettingsIgnored` and
    BEFORE the `snapshotLog`/`ensureClean` block:
    ```scala
    val settingsPath = OrcaDir.settingsPath(workDir)
    val settingsFileExisted = os.exists(settingsPath)
    val stackSettings = settingsOverride.getOrElse:
      if settingsFileExisted then
        SettingsFile.parse(os.read(settingsPath)) match
          case Right(s)  => s
          case Left(err) =>
            throw new OrcaFlowException(
              s"invalid stack settings at $settingsPath: $err"
            )
      else StackSettings.empty // Epic 4 replaces this arm with discovery
    ```
    (With an override, the file is not read — `settingsFileExisted` is
    still recorded from the exists check, which is cheap and keeps the
    field's meaning uniform.)
  - `run(...)`: after `setup` returns, call
    `ctx.populateStackSettings(flowSetup.stackSettings)` (the concrete
    `DefaultFlowContext` is in scope there — check how `run` receives it;
    if it only sees the `FlowContext` trait, accept the concrete type or
    add the populate call where the concrete value exists in `runFlow`).
- Test: `runner/src/test/scala/orca/runner/FlowLifecycleTest.scala`, using
  the existing direct-`setup` pattern and `GitRepo.seeded()`:
  1. committed settings file → `FlowSetup.stackSettings` matches parsed
     content, `settingsFileExisted = true`;
  2. UNTRACKED settings file in an otherwise dirty tree → values read,
     `settingsFileExisted = true` (proves read-before-stash: after setup,
     the file may be stashed away but the values are already held);
  3. malformed file → `OrcaFlowException` thrown AND `git stash list` is
     empty AND current branch unchanged (aborted before mutation);
  4. override present + file present with different values → override
     wins, file untouched;
  5. no file, no override → `StackSettings.empty`, `settingsFileExisted = false`.

- [ ] **Step 1:** failing tests (all five arms).
- [ ] **Step 2:** implement; `sbt --client "runner/testOnly *FlowLifecycle*"`
      then `runner/test` — green, zero warnings.
- [ ] **Step 3:** format + commit:
      `git commit -m "Lifecycle reads stack settings pre-ensureClean, populates the context"`

### Task 4: full-build gate

- [ ] `sbt --client test` all green; `git status` clean; report SHAs,
      deviations, and the exact `setup`/`FlowSetup` signatures Epic 4
      builds on.

## Self-review notes

ADR coverage (read half): pre-`ensureClean` read+validate with pre-mutation
abort (T3 arm 3), existence decided at the pre-stash read (T3 arm 2 +
`settingsFileExisted`), override precedence with no file I/O (T3 arm 4,
T2), frozen-per-run via one-shot slot (T1). Discovery, write sequencing,
events: Epic 4. `Configured` consumption: Epic 5.
