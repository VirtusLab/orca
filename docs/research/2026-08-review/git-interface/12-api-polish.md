# API polish: `ensureClean`'s unused Boolean and the `diff()` naming trap

**Aspect**: complexity  **Severity**: low

## Problem

Two small API shapes that invite misreading:

1. **`ensureClean` returns a Boolean production provably ignores.** Both
   callers discard it
   (`runner/src/main/scala/orca/runner/FlowLifecycle.scala:469, 479`):

   ```scala
   val _ = git.ensureClean("orca: starting flow")
   ```

   Only `tools/src/test/scala/orca/tools/OsGitToolTest.scala:245-256`
   asserts the value, and the user-visible outcome is already carried by
   the emitted `Step` event (GitTool.scala:569-573). A meaning-laden
   Boolean return ("was a stash created?") that nothing consumes is
   boolean-blindness with no payoff.

2. **`diff()` vs `diffVsBase()` is a trap the docs keep re-warning about.**
   The warning "NOT `git.diff()` (vs HEAD), which is empty here" appears at
   `flow/src/main/scala/orca/pr/openPrFromBranch.scala:15`,
   `flows/issue-pr-bugfix.sc:159`, and in generated example comments —
   three independent restatements of one misuse. `diff` sounds like "the
   diff of my work", but after a stage commit it is empty; the name carries
   too little.

## Proposed solution

1. Change `ensureClean` to return `Unit` (trait + impl + README row). The
   two `val _ =` call sites simplify; the test at OsGitToolTest.scala:245
   asserts via the captured listener instead (the stash test at 251 already
   does).
2. Rename the script-facing `diff()` to state its scope, e.g.
   `uncommittedDiff(): String`. Update README (rows at 181 and the example
   at ~302), `FlowCompilesTest.scala:212`, and drop the now-redundant
   call-site warnings at openPrFromBranch.scala:15 and
   flows/issue-pr-bugfix.sc:159 (keep one sentence in the trait doc). Orca
   is 0.x; script consumers are version-pinned, so no compat shim.

Must NOT change: `ensureClean`'s stash-and-`Step` behavior and its
stash-recovery-hint contract; `diff()`'s semantics (tracked, vs HEAD,
`.orca/`-excluded).

## Verification

**Verdict: CONFIRMED-REVISED** (approach unchanged; rename touch list completed).

Checked the `val _ = git.ensureClean(...)` discards at FlowLifecycle.scala:469/479 (verbatim); the only value assertions are OsGitToolTest.scala:245-249 and 251-256. The `diff()` warning appears at openPrFromBranch.scala:15, flows/issue-pr-bugfix.sc:159, and generated example artifacts. No other consumer of `ensureClean`'s Boolean exists (grep; remaining mentions are comments).

Solution revision — the rename must also catch `tools/src/main/scala/orca/backend/SystemPromptComposer.scala:27`, which mentions "`git.diff()` empty" in a scaladoc. The examples/.scala-build copies are generated caches; only the flows/*.sc sources need editing. Full rename touch list: GitTool.scala trait+impl, README rows 181 and ~302, FlowCompilesTest.scala:212, openPrFromBranch.scala:15 (delete warning), flows/issue-pr-bugfix.sc:159 (delete warning), SystemPromptComposer.scala:27, OsGitToolTest call sites (~122-129, 258, 637-646). If finding 04 lands, its surviving-family listing uses the new `uncommittedDiff()` name.
