package orca.tools.claude.streamjson

import orca.util.RawJson

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec

/** Messages the driver writes back to claude's stdin.
  *
  * The inbound schema is reverse-engineered from third-party references
  * (`claude-code-parser` etc.) — Anthropic does not publish the stdin wire
  * format. Covered by integration tests pinned against the installed CLI
  * version so drift is caught on CI.
  */
private[claude] enum OutboundMessage:
  case UserText(text: String)

  /** Response to a `control_request` tool-approval prompt. */
  case ControlResponse(requestId: String, decision: ControlDecision)

private[claude] enum ControlDecision:
  case Allow(updatedInput: Option[String] = None)
  case Deny(reason: Option[String] = None)

private[claude] object OutboundMessage:

  /** Serialize to a single NDJSON line (no trailing newline — the caller
    * appends one).
    */
  def toJson(msg: OutboundMessage): String = msg match
    case UserText(text) =>
      writeToString(UserTextWire.of(text))
    case ControlResponse(requestId, ControlDecision.Allow(updated)) =>
      writeToString(
        ControlResponseWire.allow(requestId, updated.map(RawJson.apply))
      )
    case ControlResponse(requestId, ControlDecision.Deny(reason)) =>
      writeToString(ControlResponseWire.deny(requestId, reason))

  // --- Wire shapes ---

  private case class UserTextContent(`type`: String, text: String)
      derives ConfiguredJsonValueCodec

  private case class UserTextInner(
      role: String,
      content: List[UserTextContent]
  ) derives ConfiguredJsonValueCodec

  private case class UserTextWire(
      `type`: String,
      message: UserTextInner
  ) derives ConfiguredJsonValueCodec

  private object UserTextWire:
    def of(text: String): UserTextWire = UserTextWire(
      `type` = "user",
      message = UserTextInner(
        role = "user",
        content = List(UserTextContent("text", text))
      )
    )

  private case class ControlResponseInner(
      subtype: String,
      request_id: String,
      behavior: String,
      updatedInput: Option[RawJson] = None,
      message: Option[String] = None
  ) derives ConfiguredJsonValueCodec

  private case class ControlResponseWire(
      `type`: String,
      response: ControlResponseInner
  ) derives ConfiguredJsonValueCodec

  private object ControlResponseWire:
    def allow(
        requestId: String,
        updatedInput: Option[RawJson]
    ): ControlResponseWire = ControlResponseWire(
      `type` = "control_response",
      response = ControlResponseInner(
        subtype = "can_use_tool_response",
        request_id = requestId,
        behavior = "allow",
        updatedInput = updatedInput
      )
    )

    def deny(requestId: String, reason: Option[String]): ControlResponseWire =
      ControlResponseWire(
        `type` = "control_response",
        response = ControlResponseInner(
          subtype = "can_use_tool_response",
          request_id = requestId,
          behavior = "deny",
          message = reason
        )
      )
