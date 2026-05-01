package orca.io

import orca.{LlmConfig, Prompts}

/** Default [[Prompts]] implementation. Each method builds the final prompt
  * string via Scala string interpolation.
  *
  * Autonomous calls ship the JSON Schema inline in the prompt — they route
  * through `claude -p --output-format json` with no structured validation on
  * the CLI side. Interactive calls rely on `--json-schema` for enforcement and
  * let the agent reply in natural conversation until it has the final
  * structured value; the schema is still summarised in the prompt so the model
  * knows the target shape, but no magic marker is required.
  */
object DefaultPrompts extends Prompts:

  private val RawJsonRules: String =
    """- no surrounding prose or commentary
      |- no markdown code fences
      |- raw JSON only""".stripMargin

  def autonomous(
      input: String,
      outputSchema: String,
      config: LlmConfig
  ): String =
    s"""Complete the task described in the input. Respond with a JSON value that
       |conforms to the output schema below. Rules:
       |$RawJsonRules
       |
       |Input:
       |$input
       |
       |Output schema:
       |$outputSchema
       |""".stripMargin

  def interactive(
      input: String,
      outputSchema: String,
      config: LlmConfig
  ): String =
    s"""Collaborate with the user on the task described in the input. Use
       |prose for status updates, questions to the user, and progress
       |commentary — any of these can appear mid-session and will not be
       |interpreted as the final answer.
       |
       |When you are ready to deliver the final answer, send one last
       |message containing ONLY a JSON value that conforms to the schema
       |below. Rules for the final answer:
       |$RawJsonRules
       |
       |Do not invoke any "structured-output" or "final-answer" tool —
       |no such tool exists in this environment. The final JSON-only
       |message IS the delivery mechanism; the runtime parses it.
       |
       |Input:
       |$input
       |
       |Output schema:
       |$outputSchema
       |""".stripMargin

  def retry(failedResponse: String, parseError: String): String =
    s"""Your previous response could not be parsed as a JSON value matching the
       |required schema.
       |
       |Parser error:
       |$parseError
       |
       |Previous response:
       |--- BEGIN RESPONSE ---
       |$failedResponse
       |--- END RESPONSE ---
       |
       |Please produce a valid JSON value matching the schema from the original
       |instructions. Rules:
       |$RawJsonRules
       |""".stripMargin
