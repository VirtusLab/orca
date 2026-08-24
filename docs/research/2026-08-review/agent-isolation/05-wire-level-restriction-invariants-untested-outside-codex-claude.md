# The wire-level restriction invariants are test-pinned only on codex and claude

**Aspect**: correctness  **Severity**: medium

## Problem

Two invariants the enforcement matrix rests on are asserted on the wire for
some backends and merely commented on for the rest. `EnforcementTableTest`
cannot catch either regression — it queries only the classification, never the
argv or prompt.

**(a) The `PromptOnly` prompt rule.** `SystemPromptComposer.ReadOnlyTurn`'s
scaladoc (`tools/src/main/scala/orca/backend/SystemPromptComposer.scala:61-66`)
says the text "is what makes a `PromptOnly` cell … true by construction". Each
backend must route every spawn path through `combine`/`foldIntoPrompt`
(`ClaudeBackend.scala:355`, `CodexBackend.scala:180`, `GeminiBackend.scala:158`,
`OpencodeArgs.scala:65`, `PiBackend.scala:186`) — but only
`codex/src/test/scala/orca/tools/codex/CodexBackendTest.scala:223` asserts
`ReadOnlyTurn` actually reaches the wire. A new spawn path (or a sixth backend)
that skips the composer compiles and passes everything, and its `PromptOnly`
cells silently mean "nothing at all". This is exactly the weakest tier of the
matrix — gemini's read-only cells are `PromptOnly` — resting on an unpinned
convention.

**(b) Dispatch-invariance of restriction flags.** Gemini's and pi's
`Fresh | Resumed` collapse is justified by comments — `GeminiArgs.scala:99`
("[[resume]] carries `approvalArgs`"), `PiArgs.scala:81` ("[[rpc]] emits
`toolsArgs` alongside `--continue`") — and the code agrees today. But
`GeminiArgsTest.scala`'s resume tests assert only `--resume`/`-p`/`--model`,
and `PiArgsTest.scala`'s only `--continue`; neither asserts the restriction
flags on the resumed argv. Contrast `ClaudeArgsTest.scala` ("a resumed
ReadOnly turn re-emits --tools") and `CodexBackendTest`'s resumed-sandbox
tests. Dropping `approvalArgs(config)` from `GeminiArgs.resume` would silently
falsify the table's dispatch-invariance with everything green.

## Proposed solution

No production code; add the missing pins.

For (a), a shared testkit assertion in `tools/src/test/scala/orca/testkit/`
(published to the backend modules via the existing `tools % test->test`
dependency):

```scala
/** Asserts a backend's ReadOnly wire form carries the read-only rule —
  * the invariant that makes a PromptOnly enforcement cell true. */
def assertCarriesReadOnlyRule(wireForm: String): Unit =
  assert(wireForm.contains(SystemPromptComposer.ReadOnlyTurn), ...)
```

plus one test in each of claude/gemini/opencode/pi applying it to that
backend's captured spawn for a `ToolSet.ReadOnly` turn — claude: the
`--append-system-prompt-file` contents; gemini: the folded `-p` prompt;
opencode: the message body's `system` field; pi: the `--append-system-prompt`
file. The capture plumbing (StubCliRunner / stubbed server) already exists in
each module's backend tests. Codex already has its test; leave it.

For (b), two argv tests:

- `GeminiArgsTest`: `resume(...)` with `ToolSet.ReadOnly` contains
  `--approval-mode plan`; with `NetworkOnly` also `--allowed-tools web_fetch`.
- `PiArgsTest`: `rpc(dir, resume = true, config(ReadOnly), ...)` contains the
  `--tools` read-only allowlist.

Must NOT change: any production argv/prompt assembly; the existing codex and
claude tests; the one-scenario-per-test rule (each new pin is its own test).

## Verification

**Verdict: CONFIRMED-REVISED** (approach unchanged; helper demoted to optional, exact pins specified).

Checked SystemPromptComposer.scala:61-66 (ReadOnlyTurn scaladoc — exact); all five composer call sites at the cited lines; `ReadOnlyTurn` appears in exactly one backend test — CodexBackendTest.scala:223 (grepped all five modules); GeminiArgsTest resume tests (79-93) assert only `--resume`/`-p`/`--model`, PiArgsTest resume test (17-20) only `--session-dir`/`--continue`; the claude contrast test exists (ClaudeArgsTest.scala:165). All claims hold.

Solution revision: the shared testkit helper is optional — `SystemPromptComposer` is `private[orca]` and visible from every backend module's tests, so each new test may simply assert `wireForm.contains(SystemPromptComposer.ReadOnlyTurn)` directly (CodexBackendTest:223 already does); add the 5-line helper only if the failure message earns it. For opencode, the cheapest wire-form capture is `OpencodeArgs.message(config, …).system` in `OpencodeArgsTest` (same package, no server stub needed). Exact new argv pins: `GeminiArgs.resume(sid, "x", AgentConfig().copy(tools = ToolSet.ReadOnly))` contains `--approval-mode plan` (second test: `NetworkOnly` also contains `--allowed-tools web_fetch`); `PiArgs.rpc(dir, resume = true, AgentConfig().copy(tools = ToolSet.ReadOnly), None)` contains `Seq("--tools", "read,grep,find,ls")`.

Ordering: if finding 03 lands, opencode's ReadOnly wire form changes (webfetch/task disabled) but the `system`-field pin here is unaffected; land after 02/03 anyway to write pins against the final gate.
