package orca.plan

import orca.agents.{Agent, BackendTag, JsonData, SessionId, ToolSet}
import orca.testkit.{ScriptedBackend, TestAgent}

/** An agent whose every structured turn answers `value`, recording the tool
  * tier and session each turn ran with. One stub serves every autonomous
  * planning operation — pass a `Plan`, `AssessedPlan`, or `BugTriage`.
  */
private[plan] class CannedResult[T: JsonData](value: T):

  /** The tool tier of the most recent turn, so tests can assert which
    * capability a helper selected (e.g. planners use `NetworkOnly`).
    */
  var lastToolSet: Option[ToolSet] = None

  /** The session of the most recent turn, so tests can assert the returned
    * [[orca.agents.Chat]] continues that conversation.
    */
  var lastSession: Option[SessionId[BackendTag.ClaudeCode.type]] = None

  val agent: Agent[BackendTag.ClaudeCode.type] =
    TestAgent(ScriptedBackend.replying(BackendTag.ClaudeCode): turn =>
      lastToolSet = Some(turn.config.tools)
      lastSession = Some(turn.session)
      ScriptedBackend.json(value)
    )
