package orca.tools.opencode

import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import orca.util.RawJson

/** Wire DTOs for the OpenCode HTTP server (ADR 0014).
  *
  * Every shape declares only the fields orca reads or writes; jsoniter's
  * default config skips the rest, so server-side protocol additions don't break
  * decoding. [[orca.util.RawJson]] holds variable-shape subtrees (the output
  * schema we send, the `structured` payload and tool `input` we read).
  *
  * Read shapes give every field a default so a partial/early message frame
  * still decodes; write shapes (`MessageBody`, the reply bodies) are exact.
  */
private[opencode] object OpencodeApi:

  // --- Request bodies (written) ---

  /** `model` on a message / session-create body. */
  case class ModelRef(providerID: String, modelID: String)
      derives ConfiguredJsonValueCodec

  /** A `text` message part — the only part kind orca sends. */
  case class MessagePart(`type`: String, text: String)
      derives ConfiguredJsonValueCodec

  /** `format` for structured output; `schema` is the caller's JSON Schema held
    * verbatim.
    */
  case class OutputFormat(`type`: String, schema: RawJson)
      derives ConfiguredJsonValueCodec

  /** Body of `POST /session/{id}/prompt_async` (and `…/message`). */
  case class MessageBody(
      parts: List[MessagePart],
      model: Option[ModelRef] = None,
      system: Option[String] = None,
      agent: Option[String] = None,
      tools: Option[Map[String, Boolean]] = None,
      format: Option[OutputFormat] = None
  ) derives ConfiguredJsonValueCodec

  /** Body of `POST /session`. */
  case class SessionCreateBody(title: Option[String] = None)
      derives ConfiguredJsonValueCodec

  /** Body of `POST /question/{id}/reply` — one answer (selected labels) per
    * question, in order.
    */
  case class QuestionReplyBody(answers: List[List[String]])
      derives ConfiguredJsonValueCodec

  /** Body of `POST /permission/{id}/reply`; `reply` is one of
    * [[PermissionReply]].
    */
  case class PermissionReplyBody(reply: String) derives ConfiguredJsonValueCodec

  /** The verdicts `POST /permission/{id}/reply` accepts. */
  object PermissionReply:
    val Once: String = "once"
    val Always: String = "always"
    val Reject: String = "reject"

  // --- Response bodies (read) ---

  /** `POST /session` response. */
  case class SessionCreated(id: String) derives ConfiguredJsonValueCodec

  // --- Assistant message metadata (read from `message.updated`) ---

  case class Cache(read: Long = 0L, write: Long = 0L)
      derives ConfiguredJsonValueCodec

  case class Tokens(
      input: Long = 0L,
      output: Long = 0L,
      reasoning: Long = 0L,
      cache: Cache = Cache()
  ) derives ConfiguredJsonValueCodec

  /** The assistant `info` carried by `message.updated`. `structured` is present
    * only in structured mode; `error` is present only on a failed turn.
    */
  case class AssistantInfo(
      role: Option[String] = None,
      sessionID: Option[String] = None,
      structured: Option[RawJson] = None,
      tokens: Option[Tokens] = None,
      cost: Option[BigDecimal] = None,
      modelID: Option[String] = None,
      finish: Option[String] = None,
      error: Option[RawJson] = None
  ) derives ConfiguredJsonValueCodec

  // --- ask_user / permission request payloads (read from SSE `properties`) ---

  case class QuestionOption(label: String, description: String = "")
      derives ConfiguredJsonValueCodec

  case class QuestionInfo(
      question: String,
      header: Option[String] = None,
      options: List[QuestionOption] = Nil
  ) derives ConfiguredJsonValueCodec

  case class QuestionRequest(
      id: String,
      sessionID: String,
      questions: List[QuestionInfo] = Nil
  ) derives ConfiguredJsonValueCodec

  case class PermissionRequest(
      id: String,
      sessionID: String,
      permission: String,
      patterns: List[String] = Nil
  ) derives ConfiguredJsonValueCodec
