# The autonomous auto-deny reason claims "not in the auto-approve set" even when autoApprove is All

**Aspect**: correctness  **Severity**: low

## Problem

The autonomous drain denies every `ApproveTool` request with a fixed reason —
`tools/src/main/scala/orca/backend/Conversations.scala:105-121`:

```scala
case ConversationEvent.ApproveTool(toolName, _, respond) =>
  respond(ApprovalDecision.Deny(Some(
    s"$toolName is not in the auto-approve set and " +
      "autonomous mode cannot prompt for permission")))
```

On opencode, a server whose user config says `permission: ask` (the `Ignored`
`Full` cell's documented risk, `OpencodeArgs.scala:124-128`) emits
`permission.asked` → `ConversationEvent.ApproveTool`
(`opencode/src/main/scala/orca/tools/opencode/OpencodeConversation.scala:122`)
even when the turn's `autoApprove` is `AutoApprove.All`. The denial then tells
the agent (and the `OrcaEvent.Error` tells the user) that the tool "is not in
the auto-approve set" — false: `All` has no set, and the actual cause is that
the server asked and autonomous mode has no one to answer. The wording also
contradicts `EnforcementNotice.unmetRequest`'s premise that `Full`+`All` "asked
for nothing it can fail to get" (`EnforcementNotice.scala:87-99`).

Fail-safe behavior (deny is correct — dropping would deadlock, as the comment
says), so this is a wording/attribution bug only.

## Proposed solution

Make the deny reason approval-mode-aware. The drain builds the reason from the
turn's config (the `AgentConfig` is available where the drain is constructed —
follow the existing plumbing in `Conversations.scala`):

- `AutoApprove.Only(_)`: keep the current wording.
- `AutoApprove.All` (or any read-only tier): something like
  "`$toolName` needs an interactive approval the backend asked for, and
  autonomous mode cannot answer prompts".

Mirror the same split in the `OrcaEvent.Error` text. Test: one unit test in
`tools/src/test` driving the drain with an `ApproveTool` event under
`AutoApprove.All` and asserting the reason does not mention an auto-approve
set.

Must NOT change: the deny-not-drop behavior, the opencode `Ignored` cell, or
`EnforcementNotice`'s `Full`+`All` → no-notice design (that design is
deliberate; only the denial text misattributes).

## Verification

**Verdict: CONFIRMED-REVISED** (approach unchanged; the plumbing claim was wrong and is corrected).

Checked Conversations.scala:105-122 (fixed deny reason + matching `OrcaEvent.Error` text — verbatim); OpencodeConversation.scala:~120-127 (`PermissionAsked` → `ApproveTool`, unconditional); OpencodeArgs.scala:26-27/:122-128; EnforcementNotice.scala:84-99. The misattribution is real and deny-not-drop is correctly preserved.

Solution revision — the original's "the `AgentConfig` is available where the drain is constructed" is false: `Conversations.runAutonomous(session, sessions, events)` and `drainAutonomous(conv, events)` see neither the config nor the `Conversation`'s (the trait exposes only `outputSchema`). Thread it explicitly: add an `autoApprove: AutoApprove` parameter (no default) to `Conversations.drainAutonomous` and `Conversations.runAutonomous`, and pass `config.autoApprove` from the five backends' `runAutonomous` implementations (ClaudeBackend.scala:166, CodexBackend.scala:114, GeminiBackend.scala:107, OpencodeBackend.scala:91, PiBackend.scala:100). Test call sites to update mechanically: ~20 `drainAutonomous` calls in tools ConversationsTest.scala, two `runAutonomous` calls there, and one in BaseAgentTest.scala:805.

Ordering: touches the five backends' `runAutonomous` bodies — land before 06 renames them (or fold the one-argument change into 06's pass).
