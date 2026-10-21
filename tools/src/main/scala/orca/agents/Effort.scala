package orca.agents

import scala.annotation.implicitNotFound

/** How much reasoning an agent spends per turn. Set with
  * `codex.withEffort(Effort.High)`; each harness accepts the levels its
  * [[EffortSupport]] lists.
  */
enum Effort:
  case Off, Minimal, Low, Medium, High, XHigh, Max

  /** The name settings use for this level: `off`, `xhigh`, …. */
  private[orca] def label: String = toString.toLowerCase

object Effort:
  /** The level named `label` (see [[Effort.label]]). */
  private[orca] def fromLabel(label: String): Option[Effort] =
    values.find(_.label == label)

/** The [[Effort]] levels harness `B` accepts, and the value it is sent as. A
  * harness without an instance has no effort setting, so `withEffort` does not
  * compile on it.
  */
@implicitNotFound("This harness has no effort setting.")
final class EffortSupport[B <: BackendTag] private (
    tag: BackendTag,
    levels: Map[Effort, String]
):
  /** `effort`, or a message naming the levels the harness accepts. */
  private[orca] def check(effort: Effort): Either[String, Effort] =
    if levels.contains(effort) then Right(effort)
    else
      Left(
        s"${tag.harnessName} has no `${effort.label}` effort; it accepts " +
          Effort.values.filter(levels.contains).map(_.label).mkString(", ")
      )

  /** The harness's value for a level [[check]] accepted. */
  private[orca] def wire(effort: Effort): String = levels(effort)

object EffortSupport:

  /** The instance for `tag`, for code that only knows the harness at run time;
    * `None` for a harness without an effort setting.
    */
  private[orca] def forTag(tag: BackendTag): Option[EffortSupport[?]] =
    tag match
      case BackendTag.ClaudeCode => Some(claude)
      case BackendTag.Codex      => Some(codex)
      case BackendTag.Opencode   => Some(opencode)
      case BackendTag.Pi         => Some(pi)
      case BackendTag.Gemini     => None

  private val lowToMax: Map[Effort, String] = Map(
    Effort.Low -> "low",
    Effort.Medium -> "medium",
    Effort.High -> "high",
    Effort.XHigh -> "xhigh",
    Effort.Max -> "max"
  )

  given claude: EffortSupport[BackendTag.ClaudeCode.type] =
    EffortSupport(BackendTag.ClaudeCode, lowToMax)

  given codex: EffortSupport[BackendTag.Codex.type] =
    EffortSupport(
      BackendTag.Codex,
      lowToMax + (Effort.Off -> "none") + (Effort.Minimal -> "minimal")
    )

  given pi: EffortSupport[BackendTag.Pi.type] =
    EffortSupport(
      BackendTag.Pi,
      lowToMax - Effort.Max + (Effort.Off -> "off") +
        (Effort.Minimal -> "minimal")
    )

  /** Sent as the message's `variant`. Which variants exist depends on the
    * model, and orca does not look them up, so every level is accepted.
    */
  given opencode: EffortSupport[BackendTag.Opencode.type] =
    EffortSupport(
      BackendTag.Opencode,
      lowToMax + (Effort.Off -> "none") + (Effort.Minimal -> "minimal")
    )
