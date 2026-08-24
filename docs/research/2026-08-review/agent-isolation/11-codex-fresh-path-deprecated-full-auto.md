# Codex's fresh path still emits the deprecated --full-auto the resume path deliberately abandoned

**Aspect**: correctness  **Severity**: low

## Problem

`CodexArgs.sandboxArgs`
(`codex/src/main/scala/orca/tools/codex/CodexArgs.scala:157-165`) maps fresh
`NetworkOnly` and `Full`+`Only` turns to `--full-auto`, while the repo's own
resume-path documentation (`CodexArgs.scala:59-61`) records: "`--full-auto` is
accepted but deprecated, and omitted for that reason rather than because
resume refuses it" (codex-cli 0.145.0). So one tier is spelled two ways across
dispatches — resume re-asserts the read-only tiers through
`-c sandbox_mode=…` (`resumeSandboxModeArgs`, 99-104) — and the fresh spelling
is the deprecated one. If a future codex release drops the flag, fresh
`NetworkOnly` and `Full`+`Only` turns fail to spawn while resumed ones keep
working: an avoidable asymmetry.

## Proposed solution

Switch `sandboxArgs`' two `--full-auto` arms to explicit spellings the fresh
`exec` already accepts (the `ReadOnly` arm proves `--sandbox` works there):
`--sandbox workspace-write` for `NetworkOnly` (keeping the pre-subcommand
`-c sandbox_workspace_write.network_access=true` from `networkConfigArgs`) and
for `Full`+`Only`.

Caveat that gates this change: `--full-auto` may bundle an approval policy on
top of the sandbox mode. Probe once against the installed codex CLI (same
style as the dated probes at `CodexArgs.scala:62-67`) that a fresh
`--sandbox workspace-write` turn behaves identically to `--full-auto` for
approvals in `exec` (non-interactive), and record the probe date/version in
the touched scaladoc. If behavior differs, keep `--full-auto` and instead
document the deprecation asymmetry in `sandboxArgs`' doc.

Tests: update `codex/src/test/scala/orca/tools/codex/CodexArgsTest.scala`'s
fresh-argv assertions for the two arms. If the change lands together with
finding 01's `SandboxWiring`, fold it in there. Must NOT change: the resume
argv, the enforcement cells (levels are unaffected — `NetworkOnly` stays
`PromptOnly`, `Full`+`Only` stays `SandboxApprox` fresh / `Ignored` resumed),
or the rendered table.

## Verification

**Verdict: CONFIRMED-REVISED** (approach unchanged; one required omission added).

Checked CodexArgs.sandboxArgs:157-165 (fresh `NetworkOnly` and `Full`+`Only(_)` both emit `--full-auto`), the deprecation note at :59-61 (verbatim, codex-cli 0.145.0), resumeSandboxModeArgs:99-104, networkConfigArgs:172-176. The probe-gating caveat is appropriate and stays.

Solution revision — one omission: the fresh `Full`+`Only` cell's rationale string (CodexArgs.scala:221) names `--full-auto` ("the requested subset becomes `--full-auto`, a whole-sandbox approximation…") — if the flag changes, update that sentence to name `--sandbox workspace-write`, or it becomes exactly the flag/rationale drift finding 01 describes. Levels are unchanged, so the AGENTS.md rendered block needs no regeneration; the resumed-cell rationale's "`--full-auto` session" probe narrative describes the probe itself and stays as is.

Ordering: fold into 01's `SandboxWiring` pass (both rewrite CodexArgs' matches).
