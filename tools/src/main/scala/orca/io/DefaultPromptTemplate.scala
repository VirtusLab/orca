package orca.io

import orca.{LlmConfig, PromptTemplate}

/** The `PromptTemplate` Orca uses unless `orca(promptTemplate = ...)`
  * overrides it. Each method builds the final prompt string via Scala
  * string interpolation; the `DoneMarker` is exposed so the interactive
  * backend can watch for it in streamed output.
  */
object DefaultPromptTemplate extends PromptTemplate:

  val DoneMarker: String = "<<<ORCA_DONE>>>"

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
    s"""Collaborate with the user on the task described in the input. Respond
       |normally during the conversation. When the task is complete and you are
       |ready to hand back a final structured result:
       |- emit the literal marker $DoneMarker on its own line
       |- follow it with a single JSON value conforming to the output schema
       |- do not emit the marker before the task is complete
       |- do not wrap the JSON in markdown code fences
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
