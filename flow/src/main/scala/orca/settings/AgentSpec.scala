package orca.settings

import orca.agents.BackendTag

/** One configured role agent: a harness plus an optional model pin, the parsed
  * form of a `harness[:model]` settings value. The model string is passed
  * verbatim to the harness's `withModel` — orca does not normalise or validate
  * model ids.
  */
private[orca] case class AgentSpec(backend: BackendTag, model: Option[String])

private[orca] object AgentSpec:
  /** The settings-file harness name for a backend tag (`claude`, `codex`, …) —
    * lowercase, distinct from the tag's case name.
    */
  def harnessNameFor(tag: BackendTag): String = tag match
    case BackendTag.ClaudeCode => "claude"
    case BackendTag.Codex      => "codex"
    case BackendTag.Opencode   => "opencode"
    case BackendTag.Pi         => "pi"
    case BackendTag.Gemini     => "gemini"

  /** Settings-file harness names — the reverse of [[harnessNameFor]]. */
  val harnessNames: Map[String, BackendTag] =
    BackendTag.values.map(t => harnessNameFor(t) -> t).toMap

  /** Left = human-readable problem for the settings-error message. Split at the
    * FIRST `:` so a model id containing `:` survives; an empty model part means
    * no pin.
    */
  def parse(value: String): Either[String, AgentSpec] =
    val (harness, model) = value.indexOf(':') match
      case -1 => (value.trim, None)
      case i =>
        (value.take(i).trim, Some(value.drop(i + 1).trim).filter(_.nonEmpty))
    harnessNames.get(harness) match
      case Some(tag) => Right(AgentSpec(tag, model))
      case None =>
        Left(
          s"unknown harness `$harness` — valid: " +
            harnessNames.keys.toList.sorted.mkString(", ")
        )

/** The agent keys of one settings file; [[orElse]] layers one set over a
  * fallback per role — `ConfigCli` uses it to merge flag overrides over the
  * existing file. The project-over-global settings precedence itself lives in
  * `RoleAgents`, its single home.
  */
private[orca] case class AgentSettings(
    planning: Option[AgentSpec] = None,
    coding: Option[AgentSpec] = None,
    review: Option[AgentSpec] = None
):
  def orElse(fallback: AgentSettings): AgentSettings =
    AgentSettings(
      planning = planning.orElse(fallback.planning),
      coding = coding.orElse(fallback.coding),
      review = review.orElse(fallback.review)
    )

private[orca] object AgentSettings:
  val empty: AgentSettings = AgentSettings()
