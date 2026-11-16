package orca.backend.pi.rpc

import orca.json.JsonCodec

/** JSONL commands written to Pi RPC stdin. */
private[pi] object OutboundMessage:

  val PromptId: String = "orca-prompt"

  def prompt(text: String): String =
    JsonCodec.writeString(PromptCommand(PromptId, "prompt", text))

  def extensionUiValue(id: String, value: String): String =
    JsonCodec.writeString(
      ExtensionUiValueResponse("extension_ui_response", id, value)
    )

  def extensionUiCancelled(id: String): String =
    JsonCodec.writeString(
      ExtensionUiCancelledResponse("extension_ui_response", id, true)
    )

  private case class PromptCommand(id: String, `type`: String, message: String)
      derives JsonCodec

  private case class ExtensionUiValueResponse(
      `type`: String,
      id: String,
      value: String
  ) derives JsonCodec

  private case class ExtensionUiCancelledResponse(
      `type`: String,
      id: String,
      cancelled: Boolean
  ) derives JsonCodec
