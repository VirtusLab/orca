# `show` with an unmatched pathspec returns `Right("")`, and the path validator admits magic pathspecs

**Aspect**: correctness  **Severity**: medium

## Problem

Two related gaps in the agent-facing `show` path, both producing wrong-looking
answers with no error (verified empirically against git in this repo's
environment):

1. **Blank success on unmatched path.** `git show HEAD -- nosuchpath` exits 0
   and prints nothing — not even the commit message. `show`
   (`tools/src/main/scala/orca/tools/GitTool.scala:912-933`) has no
   empty-output handling, so a reviewer that typos a path in the `git_show`
   MCP tool (`tools/src/main/scala/orca/backend/mcp/RepoMcpServer.scala:60-71`)
   receives an empty result and concludes the commit did not touch that file
   — a wrong review finding, silently. The `GitReadFailed.Refused` doc
   (GitTool.scala:58-61) claims "a path not in that commit" comes back as
   `Refused`, which is true only for `fileAt` (where `cat-file -s` fails).
   The codebase already treats blank successes as a hazard: `GitRead.isRange`
   rejects `^HEAD` precisely because "an agent gets a blank answer it cannot
   tell from an empty commit" (GitTool.scala:80-84).

2. **Magic pathspecs pass validation.** `GitRead.path`
   (GitTool.scala:101-106) checks only nonEmpty / no leading `/` / no
   leading `-` / no `..` segment. `:(exclude)a.txt`, `:(top)x`, `:!x` all
   pass (verified: `git show HEAD -- ':(exclude)a.txt'` runs, exit 0, output
   silently altered). An agent that writes pathspec magic gets narrowing that
   means something else than "these files": `:(exclude)` inverts the request
   and `:(top)` escapes the `workDir` scoping — contradicting the validator's
   stated contract ("a repository-relative path"). Combined with (1), the
   result is an empty or wrongly-scoped answer with no error.

## Proposed solution

In `tools/src/main/scala/orca/tools/GitTool.scala`:

1. In `show`, when `paths.nonEmpty` and the (untruncated) output is blank,
   return
   `Left(new GitReadFailed.Refused(s"no path in $rev matched: ${paths.mkString(", ")}"))`.
   Also correct the `GitReadFailed.Refused` scaladoc to say this is how the
   unmatched-path case surfaces for `show`.
2. In `GitRead.path`, additionally reject a leading `:` (one clause in the
   existing condition), so a magic pathspec fails as `InvalidPath` — the
   error message already spells the accepted shape.

Tests (`tools/src/test/scala/orca/tools/OsGitToolTest.scala`):
`show("HEAD", paths = List("nosuch"))` is a `Left`;
`show("HEAD", paths = List(":(exclude)x"))` is `Left(InvalidPath)`.

Must NOT change: `GitRead.rev` (already correct, pinned by tests), the
`--end-of-options` placement, the truncation-marker behavior, and
`fileAt`'s existing refusal path.

## Verification

**Verdict: CONFIRMED.**

Checked GitTool.scala:912-933 (no empty-output handling), 58-61 (`Refused` doc true only for `fileAt`), 101-106 (`GitRead.path` checks only nonEmpty/leading-`/`/leading-`-`/`..`), 80-84 (`isRange` blank-answer rationale). Empirically reproduced both: `git show HEAD -- nosuchpath` → exit 0, zero bytes; `git show HEAD -- ':(exclude)a.txt'` → exit 0, silently altered output. Framed as typo/mistake — trusted-but-fallible, fine.

Implementation notes: the leading-`:` rejection cannot break a legitimate path — git parses any leading-`:` pathspec as magic, so no plain repository path is addressable that way. A blank success also occurs for a REAL path the commit didn't touch (not just typos); `Refused("no path in $rev matched: …")` is still an accurate answer there, so no case split is needed. `show` builds output inside an `either:` block — the blank check goes after the `gitRead(...).ok()` call, on the untruncated case only (a truncated output is never blank).
