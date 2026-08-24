# Seven best-effort read probes hand-roll the same shape; `origin/HEAD` is resolved twice

**Aspect**: conciseness  **Severity**: medium

## Problem

The shape "run `gitProc`, exit 0 → `Some(stdout)`, else / `NonFatal` →
`None`" is written out in full seven times in
`tools/src/main/scala/orca/tools/GitTool.scala`:

- `revParse` (675-681)
- `isIgnored` (687-691)
- `defaultBranch` (693-703)
- `upstreamHas` (714-719)
- `workDirPrefix` (813-815)
- `resolveOriginHead` (885-893)
- `gitConfigGet` (1067-1070)

Each repeats `val result = gitProc(Seq("git", ...)); if result.exitCode == 0
then Some(...) else None`, with or without `try/catch case NonFatal(_)`.
Every "READ-ONLY, best-effort" trait contract is re-implemented by hand
instead of stated once.

Worse, two of them are the same git call: `defaultBranch` (693-703,
`symbolic-ref --short refs/remotes/origin/HEAD`, strips `origin/`) and
`resolveOriginHead` (885-893, `symbolic-ref -q refs/remotes/origin/HEAD`,
strips `refs/remotes/`) resolve the identical ref with different flags and
different prefix-stripping — duplicated plumbing that has already drifted
(`--short` vs `-q`), and two divergent contracts (`Option`-and-swallow vs
feeding `defaultBase`'s throw) for one piece of information.

## Proposed solution

In `OsGitTool`, one private helper:

```scala
/** Exit-0 stdout of a read-only git probe; `None` on non-zero exit or any
  * error — the shared shape of the trait's best-effort reads.
  */
private def probe(args: String*): Option[String] =
  try
    val r = gitProc("git" +: args)
    if r.exitCode == 0 then Some(r.out.text()) else None
  catch case NonFatal(_) => None
```

Rewritten call sites (keep the existing why-comments — `--verify`/`-q`
semantics, check-ignore exit codes, the `./` prefix note):

```scala
private def revParse(ref: String): Option[String] =
  probe("rev-parse", "--verify", "--quiet", ref).map(_.trim).filter(_.nonEmpty)

def isIgnored(relPath: os.SubPath): Boolean =
  probe("check-ignore", "-q", "--", relPath.toString).isDefined

def defaultBranch(): Option[String] =
  originHead().map(_.stripPrefix("origin/"))

private lazy val workDirPrefix: String =
  probe("rev-parse", "--show-prefix").fold("")(_.trim)
```

Delete `resolveOriginHead`; single resolution point feeding both publics:

```scala
private def originHead(): Option[String] =            // "origin/main" or None
  probe("symbolic-ref", "--short", "refs/remotes/origin/HEAD")
    .map(_.trim).filter(_.nonEmpty)

def defaultBase(): String =
  originHead()
    .orElse(List("origin/main", "origin/master").find(refExists))
    .getOrElse(throw OrcaFlowException(/* existing message */))
```

Behavior notes for the implementer: `defaultBranch` already returns `None`
on any error, so deriving it from `originHead()` preserves its contract;
`defaultBase` then falls through to the fallbacks and the existing throw.
`upstreamHas` keeps a thin outer `try` (its `subRelativeTo` throws before
the probe). `gitConfigGet` gains error-swallowing it previously lacked —
acceptable: its one caller (`push`, GitTool.scala:646) treats an unreadable
config as "no github origin", and the push itself still surfaces real git
breakage.

Estimated net saving: ~40-45 lines. Existing tests
(`OsGitToolTest.scala:131-172`, `345-383`) cover both publics and pass
unchanged.

Must NOT change: the public contracts of `defaultBranch`/`defaultBase`
(README-documented), `nonInteractiveEnv` threading through `gitProc`.

## Verification

**Verdict: CONFIRMED.**

Checked all seven sites (GitTool.scala:675-681, 683-691, 693-703, 705-719, 813-815, 885-893, 1067-1070) — the shape is hand-rolled in each; `defaultBranch` vs `resolveOriginHead` both resolve `refs/remotes/origin/HEAD` with drifted flags (`--short` vs `-q`) and different prefix stripping. Tests at OsGitToolTest.scala:131-172 and 345-383 pin exactly the contracts the rewrite preserves; `--short` output ("origin/main") equals the `refs/remotes/`-stripped form, so `defaultBase`'s value is unchanged. Genuine dedupe (one resolution point for origin/HEAD is a real correctness convergence), and the stated behavior deltas (`gitConfigGet` gains swallowing; `upstreamHas` keeps its outer try) were verified against the current code.

One additional accepted behavior delta: `workDirPrefix` currently propagates a thrown subprocess error (no try/catch today); via `probe` it degrades to `""`. Acceptable — if git itself is broken, the next non-probe `git(...)` call throws with the real error anyway.
