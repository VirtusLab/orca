package orca.tools.pi.rpc

import orca.events.Usage
import orca.util.RawJson

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonValueCodec,
  readFromString
}
import com.github.plokhotnyuk.jsoniter_scala.macros.{
  ConfiguredJsonValueCodec,
  JsonCodecMaker
}

import scala.util.control.NonFatal

/** One JSONL message read from `pi --mode rpc` stdout. The driver models only
  * fields needed by Orca and collapses protocol additions to [[Unknown]].
  */
private[pi] enum InboundEvent:
  case Response(
      id: Option[String],
      command: Option[String],
      success: Boolean,
      error: Option[String]
  )
  case MessageUpdate(delta: MessageDelta)
  case MessageEnd(message: AgentMessage)
  case AgentEnd
  case ToolExecutionStart(toolName: String, rawArgs: String)
  case ToolExecutionEnd(toolName: String, ok: Boolean, content: String)
  case ExtensionUiRequest(id: String, method: String, question: String)
  case Unknown(rawType: String)

private[pi] enum MessageDelta:
  case Text(text: String)
  case Thinking(text: String)
  case Other(deltaType: String)

private[pi] case class AgentMessage(
    role: String,
    text: String,
    usage: Option[Usage],
    model: Option[String],
    errorMessage: Option[String]
)

private[pi] object InboundEvent:

  def parse(line: String): InboundEvent =
    val envelope = readFromString[TopEnvelope](line)
    envelope.`type` match
      case "response"             => parseResponse(line)
      case "message_update"       => parseMessageUpdate(line)
      case "message_end"          => parseMessageEnd(line)
      case "agent_end"            => AgentEnd
      case "tool_execution_start" => parseToolExecutionStart(line)
      case "tool_execution_end"   => parseToolExecutionEnd(line)
      case "extension_ui_request" => parseExtensionUiRequest(line)
      case other                  => Unknown(other)

  private def parseResponse(line: String): InboundEvent =
    val wire = readFromString[ResponseWire](line)
    Response(wire.id, wire.command, wire.success.getOrElse(false), wire.error)

  private def parseMessageUpdate(line: String): InboundEvent =
    val wire = readFromString[MessageUpdateWire](line)
    wire.assistantMessageEvent match
      case Some(raw) => MessageUpdate(parseDelta(raw))
      case None      => Unknown("message_update")

  private def parseDelta(raw: RawJson): MessageDelta =
    val wire = readFromString[DeltaWire](raw.value)
    wire.`type` match
      case "text_delta" => MessageDelta.Text(wire.delta.getOrElse(""))
      case "thinking_delta" =>
        MessageDelta.Thinking(wire.delta.getOrElse(""))
      case other => MessageDelta.Other(other)

  private def parseMessageEnd(line: String): InboundEvent =
    val wire = readFromString[MessageEndWire](line)
    MessageEnd(parseAgentMessage(wire.message))

  private def parseAgentMessage(raw: RawJson): AgentMessage =
    val wire = readFromString[AgentMessageWire](raw.value)
    AgentMessage(
      role = wire.role.getOrElse(""),
      text = wire.content.map(renderContent).getOrElse(""),
      usage = wire.usage.map(_.toUsage),
      model = wire.model,
      errorMessage = wire.errorMessage
    )

  private def parseToolExecutionStart(line: String): InboundEvent =
    val wire = readFromString[ToolExecutionStartWire](line)
    ToolExecutionStart(
      wire.toolName.getOrElse(""),
      wire.args.map(_.value).getOrElse("{}")
    )

  private def parseToolExecutionEnd(line: String): InboundEvent =
    val wire = readFromString[ToolExecutionEndWire](line)
    ToolExecutionEnd(
      toolName = wire.toolName.getOrElse(""),
      ok = !wire.isError.getOrElse(false),
      content = wire.result.map(renderToolResult).getOrElse("")
    )

  private def parseExtensionUiRequest(line: String): InboundEvent =
    val wire = readFromString[ExtensionUiRequestWire](line)
    // A missing/unknown method falls through to the driver's `other` branch,
    // which replies with a cancel so Pi never blocks waiting on us.
    val method = wire.method.getOrElse("")
    val question = wire.title
      .orElse(wire.message.flatMap(renderJsonString))
      .orElse(wire.placeholder)
      .orElse(wire.prefill)
      .getOrElse(method)
    ExtensionUiRequest(wire.id, method, question)

  private def renderContent(raw: RawJson): String =
    val trimmed = raw.value.trim
    if trimmed.isEmpty || trimmed == "null" then ""
    else if trimmed.startsWith("\"") then
      try readFromString[String](trimmed)
      catch case NonFatal(_) => trimmed
    else if trimmed.startsWith("[") then
      try
        readFromString[List[ContentBlockWire]](trimmed)
          .flatMap(c => c.text.filter(_ => c.`type` == "text"))
          .mkString
      catch case NonFatal(_) => trimmed
    else trimmed

  private def renderToolResult(raw: RawJson): String =
    val trimmed = raw.value.trim
    if trimmed.isEmpty || trimmed == "null" then ""
    else
      try
        readFromString[ToolResultWire](trimmed).content
          .flatMap(c => c.text.filter(_ => c.`type` == "text"))
          .mkString
      catch case NonFatal(_) => trimmed

  private def renderJsonString(raw: RawJson): Option[String] =
    val trimmed = raw.value.trim
    if trimmed.isEmpty || trimmed == "null" then None
    else
      try Some(readFromString[String](trimmed))
      catch case NonFatal(_) => Some(trimmed)

  private case class TopEnvelope(`type`: String)
      derives ConfiguredJsonValueCodec

  private case class ResponseWire(
      id: Option[String] = None,
      command: Option[String] = None,
      success: Option[Boolean] = None,
      error: Option[String] = None
  ) derives ConfiguredJsonValueCodec

  private case class MessageUpdateWire(
      assistantMessageEvent: Option[RawJson] = None
  ) derives ConfiguredJsonValueCodec

  private case class DeltaWire(
      `type`: String,
      delta: Option[String] = None
  ) derives ConfiguredJsonValueCodec

  private case class MessageEndWire(message: RawJson)
      derives ConfiguredJsonValueCodec

  private case class AgentMessageWire(
      role: Option[String] = None,
      content: Option[RawJson] = None,
      usage: Option[UsageWire] = None,
      model: Option[String] = None,
      errorMessage: Option[String] = None
  ) derives ConfiguredJsonValueCodec

  private case class UsageWire(
      input: Option[Long] = None,
      output: Option[Long] = None,
      cacheRead: Option[Long] = None,
      cacheWrite: Option[Long] = None,
      cost: Option[CostWire] = None
  ) derives ConfiguredJsonValueCodec:
    def toUsage: Usage =
      Usage(
        inputTokens = input.getOrElse(0L),
        outputTokens = output.getOrElse(0L),
        cost = cost.flatMap(_.total),
        cachedInputTokens = cacheRead.getOrElse(0L) + cacheWrite.getOrElse(0L)
      )

  private case class CostWire(total: Option[BigDecimal] = None)
      derives ConfiguredJsonValueCodec

  private case class ToolExecutionStartWire(
      toolName: Option[String] = None,
      args: Option[RawJson] = None
  ) derives ConfiguredJsonValueCodec

  private case class ToolExecutionEndWire(
      toolName: Option[String] = None,
      result: Option[RawJson] = None,
      isError: Option[Boolean] = None
  ) derives ConfiguredJsonValueCodec

  private case class ToolResultWire(
      content: List[ContentBlockWire] = Nil
  ) derives ConfiguredJsonValueCodec

  private case class ContentBlockWire(
      `type`: String,
      text: Option[String] = None
  ) derives ConfiguredJsonValueCodec

  private given JsonValueCodec[String] = JsonCodecMaker.make[String]
  private given JsonValueCodec[List[ContentBlockWire]] =
    JsonCodecMaker.make[List[ContentBlockWire]]

  private case class ExtensionUiRequestWire(
      id: String,
      method: Option[String] = None,
      title: Option[String] = None,
      message: Option[RawJson] = None,
      placeholder: Option[String] = None,
      prefill: Option[String] = None
  ) derives ConfiguredJsonValueCodec
