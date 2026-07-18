package orca.util

import _root_.io.circe.{Json, JsonObject}
import _root_.io.circe.syntax.EncoderOps
import orca.OrcaFlowException
import sttp.apispec.circe.encoderSchema
import sttp.tapir.Schema
import sttp.tapir.docs.apispec.schema.TapirSchemaToJsonSchema

/** JSON-Schema string produced for a given Scala type's `tapir.Schema`, post-
  * processed so it satisfies OpenAI's "strict" structured-output dialect (used
  * by both `codex exec --output-schema` and `claude --json-schema`):
  *
  *   - every object node carries `additionalProperties: false`;
  *   - every object's `required` array lists every key in `properties`.
  *
  * Tapir's default output is more permissive than strict mode, so codex rejects
  * it with `invalid_json_schema`. Optional fields and fields with Scala-side
  * defaults are marked nullable via `markOptionsAsNullable = true`, so
  * requiring them is safe — the agent emits `null` or an empty list rather than
  * omitting.
  *
  * A `Map[String, _]` field has no fixed key set, so it can't be expressed in
  * the strict dialect; such a field makes this throw [[orca.OrcaFlowException]]
  * rather than emit a schema the backend would reject later and more opaquely.
  *
  * The post-processing applies to every backend, which is why it lives at the
  * backend-agnostic `resultAs[O]` seam. The one schema string per `O` is both
  * passed to native schema flags (claude `--json-schema`, codex
  * `--output-schema`) and embedded into the prompt template all backends
  * receive (`Prompts`; pi/gemini/opencode have no native flag). Strict-mode
  * output is still valid JSON Schema, just more constrained, so the strictest
  * dialect any backend requires is safe as the single common form.
  */
object JsonSchemaGen:
  def apply[O](using schema: Schema[O]): String =
    val jsonSchema =
      TapirSchemaToJsonSchema(schema, markOptionsAsNullable = true)
    // Tapir stamps `$schema: .../draft/2020-12/schema` on its output; the
    // claude CLI (observed on 2.1.207) has no 2020-12 meta-schema registered
    // and rejects any schema declaring that dialect ("no schema with key or ref
    // ...") before the turn starts. No consumer needs the declaration, so strip
    // it.
    toOpenAiStrict(jsonSchema.asJson).mapObject(_.remove("$schema")).noSpaces

  /** Walk every object subtree and inject the two OpenAI-strict-mode
    * constraints. Exposed for tests; production code uses [[apply]] which
    * applies it automatically.
    */
  private[util] def toOpenAiStrict(json: Json): Json =
    json.fold(
      jsonNull = json,
      jsonBoolean = _ => json,
      jsonNumber = _ => json,
      jsonString = _ => json,
      jsonArray = arr => Json.fromValues(arr.map(toOpenAiStrict)),
      jsonObject = obj => Json.fromJsonObject(transformObject(obj))
    )

  private def transformObject(obj: JsonObject): JsonObject =
    val recursed = JsonObject.fromIterable(
      obj.toIterable.map((k, v) => k -> toOpenAiStrict(v))
    )
    rejectMapShapedAdditionalProperties(recursed)
    if isObjectSchemaNode(recursed) then addStrictConstraints(recursed)
    else recursed

  /** Tapir renders a `Map[String, T]` field as an object node whose
    * `additionalProperties` is `T`'s own sub-schema (no `properties` key — the
    * keys are unbounded). Strict mode requires `additionalProperties: false` on
    * every object node, which would silently discard the value-type constraint
    * if overwritten — so fail fast here, with a message naming the fix, rather
    * than letting the schema bounce back as an opaque `invalid_json_schema`
    * after a stage has already run.
    */
  private def rejectMapShapedAdditionalProperties(obj: JsonObject): Unit =
    obj("additionalProperties").filterNot(_.isBoolean).foreach { _ =>
      throw OrcaFlowException(
        "resultAs[O]: output schema has a Map[String, _]-shaped field " +
          "(an object with a value-type `additionalProperties` schema), " +
          "which OpenAI's strict structured-output mode doesn't support. " +
          "Model it as a List of key/value case classes instead."
      )
    }

  /** A node is an "object schema" — eligible for the strict-mode constraints —
    * when it declares `"type": "object"` AND carries a non-empty `"properties"`
    * object. The properties check rules out empty/marker objects like
    * `{"type":"object"}`.
    */
  private def isObjectSchemaNode(obj: JsonObject): Boolean =
    obj("type").flatMap(_.asString).contains("object") &&
      obj("properties").flatMap(_.asObject).exists(_.nonEmpty)

  private def addStrictConstraints(obj: JsonObject): JsonObject =
    val props =
      obj("properties").flatMap(_.asObject).getOrElse(JsonObject.empty)
    val allKeys = props.keys.toList
    // The Map[String, T] shape was already rejected above, so
    // `additionalProperties` here is absent or already boolean — don't clobber
    // an explicit boolean, only fill in the missing default.
    val withAdditional =
      if obj.contains("additionalProperties") then obj
      else obj.add("additionalProperties", Json.False)
    withAdditional.add(
      "required",
      Json.fromValues(allKeys.map(Json.fromString))
    )
