package orca.settings

import orca.agents.{BackendTag, Effort, EffortSupport}

/** One configured role agent: a harness plus an optional model pin and effort,
  * the parsed form of a `harness[:model][@effort]` settings value. The model
  * string is passed verbatim to the harness's `withModel` — orca does not
  * normalise or validate model ids. `effort` is always one the harness accepts.
  */
private[orca] case class AgentSpec private (
    backend: BackendTag,
    model: Option[String],
    effort: Option[Effort]
):
  /** The settings value [[AgentSpec.parse]] reads back into this spec. */
  def render: String =
    backend.harnessName + model.fold("")(":" + _) +
      AgentSpec.effortSuffix(effort)

  /** This spec moved to `tag` with `model`, keeping the effort only when the
    * harness stays the same.
    */
  def repinned(tag: BackendTag, model: Option[String]): AgentSpec =
    AgentSpec(tag, model, effort.filter(_ => tag == backend))

private[orca] object AgentSpec:
  /** A spec with no effort. */
  def apply(backend: BackendTag, model: Option[String]): AgentSpec =
    new AgentSpec(backend, model, None)

  /** The `@<level>` part of a settings value; empty without an effort. */
  def effortSuffix(effort: Option[Effort]): String =
    effort.fold("")("@" + _.label)

  /** The settings value's form, for help texts and file headers. */
  final val ValueForm = "harness[:model][@effort]"

  /** Settings-file harness names — the reverse of [[BackendTag.harnessName]].
    */
  val harnessNames: Map[String, BackendTag] =
    BackendTag.values.map(t => t.harnessName -> t).toMap

  /** Reads a `harness[:model][@effort]` value; Left = human-readable problem
    * for the settings-error message. `@effort` is split off first, then the
    * rest at the FIRST `:`, so a model id containing `:` survives; an empty
    * model part means no pin.
    */
  def parse(value: String): Either[String, AgentSpec] =
    for
      (pinned, effort) <- splitEffort(value)
      (harness, model) = splitModel(pinned)
      tag <- harnessNames
        .get(harness)
        .toRight(
          s"unknown harness `$harness` — valid: " +
            harnessNames.keys.toList.sorted.mkString(", ")
        )
      checked <- checkEffort(tag, effort)
    yield new AgentSpec(tag, model, checked)

  /** `harness[:model]` as the harness and the model, if any. */
  private def splitModel(pinned: String): (String, Option[String]) =
    pinned.indexOf(':') match
      case -1 => (pinned.trim, None)
      case i  =>
        (pinned.take(i).trim, Some(pinned.drop(i + 1).trim).filter(_.nonEmpty))

  /** `effort`, if the harness accepts it. */
  private def checkEffort(
      tag: BackendTag,
      effort: Option[Effort]
  ): Either[String, Option[Effort]] =
    effort.fold(Right(None)): level =>
      EffortSupport
        .forTag(tag)
        .toRight(s"${tag.harnessName} has no effort setting")
        .flatMap(_.check(level))
        .map(Some(_))

  /** Splits off a trailing `@<level>`. Only an all-letter (or empty) suffix is
    * read as a level, so a model id like Vertex's `claude-sonnet-4@20250514`
    * survives.
    */
  private def splitEffort(
      value: String
  ): Either[String, (String, Option[Effort])] =
    value.lastIndexOf('@') match
      case -1 => Right((value, None))
      case i  =>
        val suffix = value.drop(i + 1).trim
        if !suffix.forall(_.isLetter) then Right((value, None))
        else
          val valid = Effort.values.map(_.label).mkString(", ")
          Effort
            .fromLabel(suffix)
            .map(effort => (value.take(i), Some(effort)))
            .toRight(
              if suffix.isEmpty then s"missing effort after `@` — valid: $valid"
              else s"unknown effort `$suffix` — valid: $valid"
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
