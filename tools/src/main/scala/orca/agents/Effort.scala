package orca.agents

import scala.annotation.implicitNotFound

/** How much reasoning an agent spends per turn. Set with
  * `codex.withEffort(Effort.High)`; each harness accepts the levels its
  * [[EffortSupport]] lists.
  */
enum Effort:
  case Off, Minimal, Low, Medium, High, XHigh, Max

/** The [[Effort]] levels harness `B` accepts, and the value it is sent as. A
  * harness without an instance has no effort setting, so `withEffort` does not
  * compile on it.
  */
@implicitNotFound("This harness has no effort setting.")
final class EffortSupport[B <: BackendTag] private (
    harness: String,
    levels: Map[Effort, String]
):
  /** `effort`, or `IllegalArgumentException` if the harness does not accept it.
    */
  private[agents] def validated(effort: Effort): Effort =
    if levels.contains(effort) then effort
    else
      throw IllegalArgumentException(
        s"$harness has no $effort effort; it accepts " +
          Effort.values.filter(levels.contains).mkString(", ")
      )

  /** The harness's value for a level [[validated]] accepted. */
  private[orca] def wire(effort: Effort): String = levels(effort)

object EffortSupport:
  private val upToMax: Map[Effort, String] = Map(
    Effort.Low -> "low",
    Effort.Medium -> "medium",
    Effort.High -> "high",
    Effort.XHigh -> "xhigh",
    Effort.Max -> "max"
  )

  given claude: EffortSupport[BackendTag.ClaudeCode.type] =
    EffortSupport("claude", upToMax)

  given codex: EffortSupport[BackendTag.Codex.type] =
    EffortSupport(
      "codex",
      upToMax + (Effort.Off -> "none") + (Effort.Minimal -> "minimal")
    )

  given pi: EffortSupport[BackendTag.Pi.type] =
    EffortSupport(
      "pi",
      upToMax - Effort.Max + (Effort.Off -> "off") +
        (Effort.Minimal -> "minimal")
    )

  /** Sent as the message's `variant`. Which variants exist depends on the
    * model, and orca does not look them up, so every level is accepted.
    */
  given opencode: EffortSupport[BackendTag.Opencode.type] =
    EffortSupport(
      "opencode",
      upToMax + (Effort.Off -> "none") + (Effort.Minimal -> "minimal")
    )
