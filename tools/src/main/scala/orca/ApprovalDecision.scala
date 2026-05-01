package orca

/** Channel's answer to an [[ConversationEvent.ApproveTool]] prompt.
  *
  *   - `Allow(None)` — run the tool with its original input.
  *   - `Allow(Some(json))` — run the tool but substitute the input with the
  *     supplied JSON value; useful for edit-then-approve UIs.
  *   - `Deny(reason)` — refuse the call; `reason`, if given, is surfaced back
  *     to the agent so it can adapt.
  */
enum ApprovalDecision:
  case Allow(updatedInputJson: Option[String] = None)
  case Deny(reason: Option[String] = None)
