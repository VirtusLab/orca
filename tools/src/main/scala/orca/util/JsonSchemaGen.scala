package orca.util

import _root_.io.circe.{Json, JsonObject}
import _root_.io.circe.syntax.EncoderOps
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
  * Tapir's default output is JSON-Schema-valid but more permissive than
  * OpenAI's strict mode, so codex rejects it with `invalid_json_schema`.
  * Optional fields are already marked nullable (`{"type": ["X", "null"]}`) via
  * `markOptionsAsNullable = true`, so requiring them is safe — the agent
  * emits `null` rather than omitting the field. Same applies to fields with a
  * Scala-side default (e.g. `List` defaulting to `Nil`): the agent must
  * always emit them, possibly as an empty list — which is what the consuming
  * code already handles.
  */
object JsonSchemaGen:
  def apply[O](using schema: Schema[O]): String =
    val jsonSchema =
      TapirSchemaToJsonSchema(schema, markOptionsAsNullable = true)
    toOpenAiStrict(jsonSchema.asJson).noSpaces

  /** Walk every object subtree (top-level + `$defs`) and inject the two
    * OpenAI-strict-mode constraints. Exposed for tests; production code uses
    * [[apply]] which applies it automatically.
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
    if isObjectSchemaNode(recursed) then addStrictConstraints(recursed)
    else recursed

  /** A node is an "object schema" — eligible for the strict-mode constraints
    * — when it declares `"type": "object"` AND carries a `"properties"`
    * object. The properties check rules out empty/marker objects like
    * `{"type":"object"}` which would otherwise get `additionalProperties:
    * false` with no purpose.
    */
  private def isObjectSchemaNode(obj: JsonObject): Boolean =
    obj("type").flatMap(_.asString).contains("object") &&
      obj("properties").flatMap(_.asObject).exists(_.nonEmpty)

  private def addStrictConstraints(obj: JsonObject): JsonObject =
    val props = obj("properties").flatMap(_.asObject).getOrElse(JsonObject.empty)
    val allKeys = props.keys.toList
    // Don't overwrite an existing `additionalProperties` — Tapir emits a
    // sub-schema for `Map[String, T]` fields. Letting it through means
    // codex/claude reject the schema loudly (strict mode demands `false`)
    // rather than silently losing the value-type constraint.
    val withAdditional =
      if obj.contains("additionalProperties") then obj
      else obj.add("additionalProperties", Json.False)
    withAdditional.add(
      "required",
      Json.fromValues(allKeys.map(Json.fromString))
    )
