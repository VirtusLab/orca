package orca

import java.util.concurrent.atomic.AtomicReference

/** Listener that accumulates `TokensUsed` events along two independent axes —
  * by `agent` (e.g. `claude`, `abstraction`, `performance`) and by `model`
  * (e.g. `claude-opus-4-7`, `claude-haiku-4-5`). State is held in an
  * `AtomicReference` so the tracker is safe to register across concurrent LLM
  * calls.
  *
  * Both axes share the same set of underlying calls, so summing either map
  * yields the grand total. The `model` axis stores `Option[String]` because the
  * response's reported model isn't always present; the by-model summary
  * surfaces the missing case as `(unknown)`.
  */
class CostTracker extends OrcaListener:

  private case class State(
      byAgent: Map[String, Usage] = Map.empty,
      byModel: Map[Option[String], Usage] = Map.empty
  ):
    def record(agent: String, model: Option[String], u: Usage): State = copy(
      byAgent =
        byAgent.updated(agent, byAgent.getOrElse(agent, Usage.empty) + u),
      byModel =
        byModel.updated(model, byModel.getOrElse(model, Usage.empty) + u)
    )

  private val state: AtomicReference[State] = AtomicReference(State())

  def onEvent(event: OrcaEvent): Unit = event match
    case OrcaEvent.TokensUsed(agent, model, u) =>
      val _ = state.updateAndGet(_.record(agent, model, u))
    case _ => ()

  /** Usage accumulated across every call, regardless of axis. */
  def total: Usage =
    state.get().byAgent.values.foldLeft(Usage.empty)(_ + _)

  /** Per-agent usage breakdown — keyed by `LlmTool.name`. */
  def perAgent: Map[String, Usage] = state.get().byAgent

  /** Per-model usage breakdown. `None` collects calls whose model the backend
    * didn't report and the caller didn't pin in `LlmConfig`.
    */
  def perModel: Map[Option[String], Usage] = state.get().byModel

  /** Two sections — by-agent then by-model — sorted alphabetically within each.
    * Cache hits and reasoning tokens are shown parenthetically when non-zero,
    * so a typical Claude line reads `general: 12500 in (12453 cached), 1781
    * out` and a codex line that produced reasoning reads `coder: 800 in (640
    * cached), 1200 out (300 reasoning)`. Empty string when no `TokensUsed`
    * events have been observed.
    */
  def summary: String =
    val s = state.get()
    if s.byAgent.isEmpty then ""
    else
      val agentLines = s.byAgent.toList
        .sortBy(_._1)
        .map((agent, u) => s"  $agent: ${formatUsage(u)}")
      val modelLines = s.byModel.toList
        .sortBy(_._1.getOrElse("(unknown)"))
        .map: (model, u) =>
          s"  ${model.getOrElse("(unknown)")}: ${formatUsage(u)}"
      s"""By agent:
         |${agentLines.mkString("\n")}
         |
         |By model:
         |${modelLines.mkString("\n")}""".stripMargin

  private def formatUsage(u: Usage): String =
    val cached =
      if u.cachedInputTokens > 0 then s" (${u.cachedInputTokens} cached)"
      else ""
    val reasoning =
      if u.reasoningOutputTokens > 0 then
        s" (${u.reasoningOutputTokens} reasoning)"
      else ""
    s"${u.inputTokens} in$cached, ${u.outputTokens} out$reasoning"

  /** Print the summary on its own block. Leading newline keeps the output from
    * landing on top of an active terminal status row; trailing newline ensures
    * the last line is committed.
    */
  def printSummary(): Unit =
    val s = summary
    if s.nonEmpty then println(s"\n$s")
