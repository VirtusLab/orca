package orca.tools.claude.streamjson

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec

/** Messages the driver writes to claude's stdin.
  *
  * The stdin schema is reverse-engineered from third-party references
  * (`claude-code-parser` etc.) — Anthropic does not publish it. Covered by
  * integration tests pinned against the installed CLI version so drift is
  * caught on CI.
  */
private[claude] object OutboundMessage:

  /** A user turn carrying `text`, as a single NDJSON line (no trailing newline
    * — the caller appends one).
    */
  def userText(text: String): String = writeToString(
    UserTextWire(
      `type` = "user",
      message = UserTextInner(
        role = "user",
        content = List(UserTextContent("text", text))
      )
    )
  )

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
