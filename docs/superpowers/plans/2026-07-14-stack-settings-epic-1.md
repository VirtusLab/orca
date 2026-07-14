# Epic 1: `.orca/` Layout Flip Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans
> to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.
> Spec: `adr/0019-project-stack-settings.md` (§ ".orca/ flips to
> committed-by-default"). Roadmap: `2026-07-14-stack-settings-roadmap.md`.

**Goal:** one shared helper (`OrcaDir`) owns `.orca/` + `.orca/cache/`
creation; ephemeral state (lock, lint spills) moves under `cache/` which
self-ignores via its own `.gitignore` and carries a `CACHEDIR.TAG`; the
`info/exclude` mechanism is deleted; a migration warning fires when the
settings path is gitignored.

**Architecture:** `OrcaDir` is a pure-path + idempotent-ensure object in the
tools module's top-level `orca` package (next to `OrcaFlowException`). All
current `.orca` writers (`FlowLock`, `ProgressStore`, `Lint`) route through
it. `Lint` switches from `os.pwd` to the new `FlowContext.workDir` accessor.

**Tech stack:** Scala 3 braceless, os-lib, existing test styles (munit-style?
— match whatever the module's existing tests use; tools tests live in
`tools/src/test/scala/orca/`). Build via `sbt --client`; format via
`sbt --client scalafmtAll` before every commit. Follow the repo's comment
style: present-tense facts, no plan references, no Scala-mechanics tutorials.

## Global constraints

- The progress-log `forceAdd` is load-bearing — do NOT remove or weaken it.
- Comments: update stale ones you touch (e.g. FlowLock's info/exclude story,
  Lint's spill-path comment); never reference this plan or "the old way".
- Capture checking: `Lint.scala` compiles under `captureChecking`; calls to
  `OrcaDir` methods are plain method calls and are fine, but do not
  reintroduce the inline `os.Path / "literal"` String-conversion pattern
  there (see the existing `os.SubPath` comment).
- Zero compiler warnings; each task ends with the touched modules' tests
  green and a commit.

---

### Task 1: `OrcaDir`

**Files:**
- Create: `tools/src/main/scala/orca/OrcaDir.scala`
- Create: `tools/src/test/scala/orca/OrcaDirTest.scala` (match the test
  framework used by existing tests in `tools/src/test/scala/orca/tools/`)

**Interfaces (produces — later tasks and epics rely on these exact names):**
```scala
package orca

object OrcaDir:
  /** `<workDir>/.orca` — committed project metadata lives at this root. */
  def root(workDir: os.Path): os.Path
  /** `<workDir>/.orca/settings.properties` (ADR 0019; consumed in Epic 3). */
  def settingsPath(workDir: os.Path): os.Path
  /** Idempotently ensure `.orca/` exists and return it. */
  def ensureRoot(workDir: os.Path): os.Path
  /** Idempotently ensure `.orca/cache/` exists — writing its self-ignoring
    * `.gitignore` and `CACHEDIR.TAG` before returning, so nothing can land
    * in the dir before the exclusion is in place — and return it. */
  def ensureCache(workDir: os.Path): os.Path
```

Contents written by `ensureCache`, byte-exact (tests pin them):

`.orca/cache/.gitignore`:
```
# Automatically created by orca.
*
```

`.orca/cache/CACHEDIR.TAG`:
```
Signature: 8a477f597d28d172789f06886806bc55
# This file marks .orca/cache as a cache directory, so backup tools skip it.
```

Implementation notes: `os.makeDir.all` for dirs; write the two marker files
only when absent (idempotence must not churn mtimes on every flow start —
assert unchanged mtime on second call in the test). Scaladoc on the object:
one paragraph, ADR-0019-present-tense ("committed metadata at the root,
ephemeral state under the self-ignoring cache/").

- [ ] **Step 1:** Write `OrcaDirTest`: (a) `ensureCache` creates dir +
      both files with exact contents; (b) second call is a no-op (files not
      rewritten — capture mtime or write a canary edit and assert preserved);
      (c) `settingsPath(wd) == wd / ".orca" / "settings.properties"`;
      (d) `ensureRoot` creates `.orca` only, no cache. Use a temp dir
      (`os.temp.dir()`).
- [ ] **Step 2:** Run: `sbt --client "tools/testOnly orca.OrcaDirTest"` —
      expect FAIL (class not found / compile error).
- [ ] **Step 3:** Implement `OrcaDir` exactly per the interface above.
- [ ] **Step 4:** Re-run the test — expect PASS.
- [ ] **Step 5:** `sbt --client scalafmtAll`, then commit:
      `git add tools/ && git commit -m "OrcaDir: shared .orca/ + cache/ layout helper (ADR 0019)"`

### Task 2: `FlowLock` moves to `.orca/cache/flow.lock`; `info/exclude` deleted

**Files:**
- Modify: `runner/src/main/scala/orca/runner/FlowLock.scala`
- Modify: whichever runner tests assert the lock path or steal behavior —
  find them: `grep -rn "flow.lock\|FlowLock" runner/src/test`

**Interfaces:** consumes `orca.OrcaDir.ensureCache`. Public shape of
`FlowLock` unchanged (`acquireProcess/releaseProcess/acquireWorkdir/releaseWorkdir`).

- [ ] **Step 1:** Update/extend the existing lock tests to expect
      `.orca/cache/flow.lock`, and add one asserting that after
      `acquireWorkdir`, `<git-common-dir>/info/exclude` was NOT written
      (fresh seeded repo → file absent or lacks the `.orca/flow.lock` line).
- [ ] **Step 2:** Run those tests — expect the path assertions to FAIL.
- [ ] **Step 3:** In `FlowLock`: `flowLockPath` becomes
      `OrcaDir.ensureCache(workDir) / "flow.lock"` resolved inside
      `acquireWorkdir` (replacing the `os.makeDir.all(workDir / ".orca")` +
      `excludeLockFromGit(workDir)` lines); DELETE `excludeLockFromGit`
      entirely; rewrite the scaladoc paragraph that describes it — the
      guarantee is now "the lock lives under the self-ignoring
      `.orca/cache/`, so `git add -A` can never sweep it" (worktree note: the
      cache `.gitignore` is per-worktree by construction).
- [ ] **Step 4:** Run: `sbt --client "runner/testOnly *FlowLock* *Lifecycle*"`
      (plus any test file found in Step 1) — expect PASS.
- [ ] **Step 5:** Format + commit:
      `git commit -m "FlowLock: lock lives in self-ignored .orca/cache/, info/exclude retired"`

### Task 3: `ProgressStore` creates `.orca` through `OrcaDir`

**Files:**
- Modify: `flow/src/main/scala/orca/progress/ProgressStore.scala` (the
  `os.makeDir.all(dir)` at ~line 173 becomes `OrcaDir.ensureRoot(workDir)` —
  check what `dir` is derived from; the store's path stays
  `.orca/progress-<hash>.json` and `forceAdd` is untouched)

**Interfaces:** consumes `orca.OrcaDir.ensureRoot`. No public change.

- [ ] **Step 1:** Run existing progress tests first to baseline:
      `sbt --client "flow/testOnly *Progress*"` — expect PASS.
- [ ] **Step 2:** Swap the mkdir for `OrcaDir.ensureRoot`; adjust the
      surrounding code minimally (it may need `workDir` in scope — derive
      from the store's own path parent if not).
- [ ] **Step 3:** Re-run — expect PASS (behavioral no-op refactor; if the
      existing tests wouldn't catch a broken mkdir, add one that stores into
      a fresh temp dir with no `.orca`).
- [ ] **Step 4:** Format + commit:
      `git commit -m "ProgressStore: create .orca via OrcaDir"`

### Task 4: Lint spill → `workDir`-rooted `.orca/cache/`; `FlowContext.workDir`

**Files:**
- Modify: `flow/src/main/scala/orca/FlowContext.scala` — add
  `def workDir: os.Path` (scaladoc: "the working tree the flow runs
  against"). Grep for all implementors: `grep -rn "extends FlowContext\|: FlowContext" --include="*.scala"` —
  `DefaultFlowContext` already receives `workDir` in `withDefaults`
  (thread it through to the trait member); `TestFlowContext`
  (`flow/src/test/scala/orca/TestFlowContext.scala`) gets a constructor
  param defaulting to a temp dir (or its existing repo fixture's root — read
  how it's built and pick the fixture root if one exists).
- Modify: `flow/src/main/scala/orca/review/Lint.scala:107` — replace
  `val orcaDir = os.pwd / os.SubPath(".orca")` + `os.makeDir.all(orcaDir)`
  with `val orcaDir = OrcaDir.ensureCache(ctx.workDir)`; drop the `@unused`
  on `ctx`; update the spill-path comment block (95–107): the sandbox
  argument stays ("under the working tree, NOT /tmp"), the commit-safety
  paragraph simplifies (cache/ self-ignores — no crash-window commit risk at
  all now), the `os.SubPath` CC-workaround comment goes away with the
  expression. Temp-file prefix/suffix unchanged.
- Test: `flow/src/test/scala/orca/review/LintTest.scala` — the large-output
  spill test must now assert the file (referenced in the prompt the stub
  agent captures) lives under `<workDir>/.orca/cache/` and that
  `workDir`-≠-`os.pwd` is respected: build the `TestFlowContext` with an
  explicit temp `workDir` different from `os.pwd`.

**Interfaces (produces):** `FlowContext.workDir: os.Path` — Epic 3/4 rely on
this exact member.

- [ ] **Step 1:** Extend `LintTest`'s spill scenario per above — expect FAIL
      (path still `os.pwd/.orca`).
- [ ] **Step 2:** Make the `FlowContext`/`DefaultFlowContext`/`TestFlowContext`
      and `Lint` changes.
- [ ] **Step 3:** `sbt --client "flow/test"` and
      `sbt --client "runner/compile"` (DefaultFlowContext) — expect PASS,
      zero warnings.
- [ ] **Step 4:** Format + commit:
      `git commit -m "Lint spill under workDir/.orca/cache; FlowContext.workDir"`

### Task 5: migration warning when the settings path is gitignored

**Files:**
- Modify: `runner/src/main/scala/orca/runner/FlowLifecycle.scala` — at the
  top of setup (find the existing corrupt-log / stash warning sites and
  match their event mechanism, `OrcaEvent.Step` or sibling), run
  `git check-ignore -q -- .orca/settings.properties` (via `GitTool` if it
  has a raw-proc seam — read `GitTool` and add a small
  `def isIgnored(relPath: os.SubPath): Boolean` there if that's the
  established pattern; exit 0 = ignored, exit 1 = not, other = treat as
  not-ignored, never fail the flow over the probe).
- Modify: `tools/src/main/scala/orca/tools/GitTool.scala` (+ its os-backed
  impl) if adding `isIgnored`; mirror in any scripted/test double —
  `grep -rn "GitTool" */src/test --include="*.scala" -l` first.
- Test: the runner lifecycle test file that already asserts warning events
  (grep for the stash/corrupt-log warning assertions) — add: seeded repo
  with `.orca/` in a committed `.gitignore` → flow run emits a warning event
  containing the literal line to remove (`.orca/`) and the settings path;
  repo without the ignore → no such event.

Warning text (pin in the test):
`stack settings at .orca/settings.properties are gitignored — remove the '.orca/' line from .gitignore so they can be committed (scratch now self-ignores under .orca/cache/)`

- [ ] **Step 1:** Write the failing lifecycle test (both arms).
- [ ] **Step 2:** Implement `GitTool.isIgnored` + the setup warning.
- [ ] **Step 3:** `sbt --client "runner/test tools/test"` — expect PASS.
- [ ] **Step 4:** Format + commit:
      `git commit -m "Warn at flow start when .orca settings path is gitignored"`

### Task 6: full-build gate

- [ ] **Step 1:** `sbt --client "Test/compile"` then full `sbt --client test`
      — all modules green, zero warnings.
- [ ] **Step 2:** `git status` clean (no stray files); push nothing; report:
      per-task commit list + any deviations from this plan with reasons.

## Self-review notes (spec coverage)

ADR §layout-flip requirements → tasks: shared helper (1), lock move +
info/exclude retirement (2), single `.orca` creation path (1,3,4),
`os.pwd`→`workDir` reconciliation (4), gitignore+CACHEDIR.TAG byte contents
(1), migration warning with exact-line guidance (5), forceAdd untouched (3,
global constraint). Settings file itself: Epic 2/3 (only the path constant
ships here, in `OrcaDir.settingsPath`).
