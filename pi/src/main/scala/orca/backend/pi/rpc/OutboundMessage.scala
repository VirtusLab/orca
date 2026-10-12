package orca.backend.pi.rpc

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec

/** JSONL commands written to Pi RPC stdin. */
private[pi] object OutboundMessage:

  val PromptId: String = "orca-prompt"

  def prompt(text: String): String =
    writeToString(PromptCommand(PromptId, "prompt", text))

  def extensionUiValue(id: String, value: String): String =
    writeToString(ExtensionUiValueResponse("extension_ui_response", id, value))

  def extensionUiCancelled(id: String): String =
    writeToString(
      ExtensionUiCancelledResponse("extension_ui_response", id, true)
    )

  private case class PromptCommand(id: String, `type`: String, message: String)
      derives ConfiguredJsonValueCodec

  private case class ExtensionUiValueResponse(
      `type`: String,
      id: String,
      value: String
  ) derives ConfiguredJsonValueCodec

  private case class ExtensionUiCancelledResponse(
      `type`: String,
      id: String,
      cancelled: Boolean
  ) derives ConfiguredJsonValueCodec
