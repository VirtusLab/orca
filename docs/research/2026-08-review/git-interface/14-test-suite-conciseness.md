# Test suite re-seeds by hand what `GitRepo.seeded()` provides, and carries overlapping reviewDiff tests

**Aspect**: conciseness  **Severity**: low

## Problem

1. **Hand-rolled seeding.**
   `tools/src/test/scala/orca/testkit/GitRepo.scala:23-28` defines
   `seeded()` (one `seed.txt` commit), but
   `tools/src/test/scala/orca/tools/OsGitToolTest.scala` uses only
   `GitRepo.empty()` (via `withRepo`, lines 17-19). ~25-28 tests open with
   the same two-line prologue whose only purpose is "have a HEAD" (e.g.
   493-495, 505-507, 519-521, 537-539, 586-588, 644-646, 653-655, 686-688,
   785-787, 825-827, 925-927), with arbitrary seed-file-name variety
   (`x.txt`, `f.txt`, `file.txt`, `a.txt`, `initial.txt`) that is pure
   noise. ~55 lines of setup an existing fixture already absorbs.

2. **Overlapping reviewDiff tests.** The composition test at 720-730
   ("composes a tracked modification with an untracked file": asserts
   `-old`, `+new`, `+fresh`) fully subsumes the two single-case tests at
   519-526 ("includes a new untracked file") and 528-535 ("includes a
   tracked file's modification"). The project convention is no
   overlapping/redundant tests.

3. **Push test inlines its helper's setup.** The test at 315-333 re-rolls
   the bare-remote setup that `pushToLocalRemote` (338-343, defined just
   below it) encapsulates; having the helper return the remote path would
   save ~5 lines.

## Proposed solution

1. Add alongside `withRepo`:

   ```scala
   private def withSeededRepo(body: (OsGitTool, os.Path) => Unit): Unit =
     val dir = GitRepo.seeded()
     body(new OsGitTool(dir), dir)
   ```

   Convert every test whose seed content is arbitrary (~25); tests that
   diff specific seed content (e.g. 122-129, 528-535 if kept) keep their
   own writes.
2. Delete the tests at 519-526 and 528-535, keeping 720-730.
3. Make `pushToLocalRemote` return the remote path and use it in the
   315-333 test.

Estimated net saving: ~65-70 lines. (Coordinate with findings 03/04, which
delete or port other test blocks — do this one after those land.)

Must NOT change: `GitRepo`'s published `tools % test->test` surface, the
`.orca/`-exclusion contract tests (577-584 and 634-642 both stay — they pin
the same pathspec through two different operations, which is deliberate
per-contract coverage).

## Verification

**Verdict: CONFIRMED.**

Checked GitRepo.scala:14-28 (`seeded()` exists, `seed.txt`/"seed" commit), OsGitToolTest.scala:17-19 (uses only `GitRepo.empty()`), the two-line seed prologues across the cited tests, the composition test at ~720-731 (asserts `-old`/`+new`/`untracked.txt`/`+fresh`, fully covering the two single-case tests at ~519-535), and the push test at ~314-333 vs `pushToLocalRemote` (~335-343, returns `Unit`; the test needs the remote path for its `for-each-ref` assertion, so the return-the-path change is right). The "convert only tests whose seed content is arbitrary" carve-out correctly protects tests that diff seed content or assert the seed commit's own Step event (e.g. the Step-event test asserts "Committed: initial seed"). Sequencing note (do after 03/04) is correct.
