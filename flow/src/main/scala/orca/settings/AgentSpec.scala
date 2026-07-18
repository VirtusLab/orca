package orca.settings

import orca.agents.BackendTag

/** One configured role agent: a harness plus an optional model pin, the parsed
  * form of a `harness[:model]` settings value. The model string is passed
  * verbatim to the harness's `withModel` — orca does not normalise or validate
  * model ids.
  */
private[orca] case class AgentSpec(backend: BackendTag, model: Option[String])

private[orca] object AgentSpec:
  /** Settings-file harness names — deliberately lowercase and distinct from
    * [[BackendTag.wireName]] (which is frozen for the progress log).
    */
  val harnessNames: Map[String, BackendTag] = Map(
    "claude" -> BackendTag.ClaudeCode,
    "codex" -> BackendTag.Codex,
    "opencode" -> BackendTag.Opencode,
    "pi" -> BackendTag.Pi,
    "gemini" -> BackendTag.Gemini
  )

  /** The settings-file harness name for a backend tag — the reverse of
    * [[harnessNames]]. Used by the role-announcement `Step` to render a
    * resolved role's harness (`claude`, `codex`, …).
    */
  val harnessNameFor: Map[BackendTag, String] = harnessNames.map(_.swap)

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

/** The agent keys of one settings file; [[orElse]] layers a file over a
  * fallback per role (project over user-global).
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
