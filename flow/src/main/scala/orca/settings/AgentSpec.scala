package orca.settings

import orca.agents.{BackendTag, Effort, EffortSupport}

/** One configured role agent: a harness plus an optional model pin and effort,
  * the parsed form of a `harness[:model][@effort]` settings value. The model
  * string is passed verbatim to the harness's `withModel` — orca does not
  * normalise or validate model ids. `effort` is one the harness accepts.
  */
private[orca] case class AgentSpec(
    backend: BackendTag,
    model: Option[String],
    effort: Option[Effort] = None
):
  /** The settings value [[AgentSpec.parse]] reads back into this spec. */
  def render: String =
    AgentSpec.harnessNameFor(backend) + model.fold("")(":" + _) +
      effort.fold("")("@" + _.label)

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
    val (pinned, effort) = splitEffort(value)
    val (harness, model) = pinned.indexOf(':') match
      case -1 => (pinned.trim, None)
      case i =>
        (pinned.take(i).trim, Some(pinned.drop(i + 1).trim).filter(_.nonEmpty))
    for
      tag <- harnessNames
        .get(harness)
        .toRight(
          s"unknown harness `$harness` — valid: " +
            harnessNames.keys.toList.sorted.mkString(", ")
        )
      checked <- effort match
        case None => Right(None)
        case Some(level) =>
          EffortSupport
            .forTag(tag)
            .toRight(s"$harness has no effort setting")
            .flatMap(_.check(level))
            .map(Some(_))
    yield AgentSpec(tag, model, checked)

  /** Splits off a trailing `@<level>`. Only an effort level name counts, so a
    * model id containing `@` (Vertex's `claude-sonnet-4@20250514`) survives.
    */
  private def splitEffort(value: String): (String, Option[Effort]) =
    value.lastIndexOf('@') match
      case -1 => (value, None)
      case i =>
        Effort.fromLabel(value.drop(i + 1).trim) match
          case Some(effort) => (value.take(i), Some(effort))
          case None         => (value, None)

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
  def get(key: AgentKey): Option[AgentSpec] = key match
    case AgentKey.PlanningAgent => planning
    case AgentKey.CodingAgent   => coding
    case AgentKey.ReviewAgent   => review

  def updated(key: AgentKey, spec: Option[AgentSpec]): AgentSettings =
    key match
      case AgentKey.PlanningAgent => copy(planning = spec)
      case AgentKey.CodingAgent   => copy(coding = spec)
      case AgentKey.ReviewAgent   => copy(review = spec)

  /** The set roles, in planning/coding/review order. */
  def entries: List[(AgentKey, AgentSpec)] =
    AgentKey.values.toList.flatMap(key => get(key).map(key -> _))

  def orElse(fallback: AgentSettings): AgentSettings =
    AgentKey.values.foldLeft(this): (acc, key) =>
      acc.updated(key, get(key).orElse(fallback.get(key)))

private[orca] object AgentSettings:
  val empty: AgentSettings = AgentSettings()
