package orca.tools.claude.streamjson

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec

/** The `request` sub-object of a `control_request` message. Today the only
  * subtype we act on is `can_use_tool` — everything else becomes
  * `Unknown(rawSubtype)` so the driver can log and move on.
  */
private[claude] enum ControlRequestBody:
  case CanUseTool(toolName: String, rawInput: String)
  case Unknown(rawSubtype: String)

private[claude] object ControlRequestBody:

  /** `rawJson` is the `request` object verbatim. */
  def parse(rawJson: String): ControlRequestBody =
    val envelope = readFromString[SubtypeEnvelope](rawJson)
    envelope.subtype match
      case "can_use_tool" =>
        val wire = readFromString[CanUseToolWire](rawJson)
        CanUseTool(toolName = wire.tool_name, rawInput = wire.input.value)
      case other => Unknown(other)

  // --- Wire shapes ---

  private case class SubtypeEnvelope(subtype: String)
      derives ConfiguredJsonValueCodec

  private case class CanUseToolWire(tool_name: String, input: RawJson)
      derives ConfiguredJsonValueCodec
