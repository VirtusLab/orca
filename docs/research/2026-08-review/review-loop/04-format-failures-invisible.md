# A failing format command is invisible for the whole run, and the shell incantation is duplicated with the lint gate

**Aspect**: complexity  **Severity**: medium

## Problem

`flow/src/main/scala/orca/review/ReviewLoop.scala:640-644`:

```scala
private def formatWorkspace()(using WorkspaceWrite): Unit =
  formatCommands.foreach: cmd =>
    val _ = os
      .proc("bash", "-c", cmd)
      .call(cwd = ctx.workDir, check = false, mergeErrIntoOut = true)
```

`check = false` plus `mergeErrIntoOut = true` plus discarding the result means a format command that is a typo, missing from PATH, or failing on every file produces zero signal — no Step, no event — and silently re-fails before every round, up to `maxIterations + 1` times. The user's only clue is reviewers nitpicking formatting the config was supposed to prevent. The sibling `lint` path (Lint.scala:75-80) deliberately carries exit codes and output to the summariser so failures are not hidden — two adjacent shell-command features with opposite failure-visibility policies. Ignoring the exit status for *flow control* is right (the comment at 630-632 says a formatter failure shouldn't abort review); ignoring it for *reporting* violates AGENTS.md's own rule that degradations are stated.

Secondary: the `os.proc("bash", "-c", cmd).call(cwd = ctx.workDir, check = false, mergeErrIntoOut = true)` incantation appears verbatim here and in Lint.scala:76-79. It encodes a real invariant — stderr must be captured or subprocess output tears the renderer's status row (AGENTS.md, Library section) — and that invariant currently lives in two places a third caller can copy wrong.

## Proposed solution

**1. Extract one shell helper**, e.g. in Lint.scala (or a small `Shell.scala` in `orca.review` — it needs no capture checking):

```scala
/** Run `cmd` via `bash -c` in the flow's workDir. Never throws on a nonzero
  * exit; stderr is merged into the captured output so neither stream reaches
  * the terminal and tears the status row.
  */
private[review] def runShell(cmd: String)(using ctx: FlowContext): os.CommandResult =
  os.proc("bash", "-c", cmd)
    .call(cwd = ctx.workDir, check = false, mergeErrIntoOut = true)
```

Use it from both `formatWorkspace` and `lint`; keep the stderr-capture comment on the helper only.

**2. Report format failures.** In `formatWorkspace`, emit one `OrcaEvent.Step` per failing command per round:

```scala
private def formatWorkspace()(using WorkspaceWrite): Unit =
  formatCommands.foreach: cmd =>
    val r = runShell(cmd)
    if r.exitCode != 0 then
      ctx.emit(OrcaEvent.Step(
        s"format command failed (exit ${r.exitCode}): $cmd"))
```

Optionally include a trimmed head of the merged output (first line or ~200 chars). If per-round repetition is judged too noisy, dedupe by threading a `reportedFormatFailures: Set[String]` field through `ReviewLoopState` — but a Step per round is honest and simpler; prefer it unless the maintainer objects. Keep `check = false`: a failing formatter must never abort the loop.

Tests (ReviewAndFixTest.scala, using the existing `StepCapture` fixture): a `formatCommands = Configured.Use(List("exit 3"))` run emits a Step naming the command and exit code; a succeeding command (`"true"`) emits no format Step. Verify `lint`'s existing tests still pass after the helper extraction.

Must NOT change: failures never abort the loop; stderr stays merged/captured (never `Inherit`); `formatWorkspace` keeps its `(using WorkspaceWrite)` parameter and stays outside the reviewer fan-out; `lint`'s exit-code/output reporting to the summariser.

## Verification

**Verdict: CONFIRMED-REVISED** (approach unchanged; open choices resolved).

Checked ReviewLoop.scala:630-644 (`formatWorkspace`, verbatim), Lint.scala:75-79 (duplicated incantation; exit codes carried to the summariser), and the format tests at ReviewAndFixTest.scala:1321-1362 (pin fail-open + ordering; unaffected by a Step emission). Core claims hold. One supporting claim is loose: AGENTS.md has no literal "degradations are stated" rule — the closest are the refusal/destructive-op rule and the `EnforcementNotice` pattern; this does not change the verdict, the zero-signal failure is factual.

Solution revision — resolve the two open choices as follows: put `runShell` in Lint.scala (both consumers are in-package; no new file). Do not implement the `reportedFormatFailures` dedupe — emit the plain Step per failing command per round, with no output excerpt: `s"format command failed (exit ${r.exitCode}): $cmd"`. In `run`'s scope call `ctx.emit` as the loop already does elsewhere. `formatWorkspace` and `lint` are the only two sites with the incantation (re-verified by grep).
