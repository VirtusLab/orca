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
  * Tapir's default output is JSON-Schema-valid but more permissive than
  * OpenAI's strict mode, so codex rejects it with `invalid_json_schema`.
  * Optional fields and fields with Scala-side defaults (`List` → `Nil`, etc.)
  * are marked nullable via `markOptionsAsNullable = true`, so requiring them is
  * safe — the agent emits `null` or an empty list rather than omitting.
  *
  * A `Map[String, _]` field has no fixed key set, so it can't be expressed in
  * OpenAI's strict dialect (which requires every object's exact key set up
  * front); such a field makes this throw [[orca.OrcaFlowException]] rather than
  * emit a schema the backend would reject anyway, later and more opaquely.
  */
object JsonSchemaGen:
  def apply[O](using schema: Schema[O]): String =
    val jsonSchema =
      TapirSchemaToJsonSchema(schema, markOptionsAsNullable = true)
    toOpenAiStrict(jsonSchema.asJson).noSpaces

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
    * `additionalProperties` is `T`'s own sub-schema (there's no `properties`
    * key — the keys are unbounded). OpenAI's strict structured-output mode
    * requires `additionalProperties: false` on every object node, which would
    * silently discard the value-type constraint if we overwrote it — so instead
    * fail fast here, at schema-generation time, with a message naming the
    * actual fix, rather than letting the non-strict schema reach codex/claude
    * and bounce back as an opaque `invalid_json_schema` after a stage has
    * already run.
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
    * when it declares `"type": "object"` AND carries a `"properties"` object.
    * The properties check rules out empty/marker objects like
    * `{"type":"object"}` which would otherwise get `additionalProperties:
    * false` with no purpose.
    */
  private def isObjectSchemaNode(obj: JsonObject): Boolean =
    obj("type").flatMap(_.asString).contains("object") &&
      obj("properties").flatMap(_.asObject).exists(_.nonEmpty)

  private def addStrictConstraints(obj: JsonObject): JsonObject =
    val props =
      obj("properties").flatMap(_.asObject).getOrElse(JsonObject.empty)
    val allKeys = props.keys.toList
    // A non-boolean `additionalProperties` (the Map[String, T] shape) was
    // already rejected by `rejectMapShapedAdditionalProperties` above, so
    // anything reaching here is either absent or already `true`/`false` —
    // don't clobber an explicit boolean, only fill in the missing default.
    val withAdditional =
      if obj.contains("additionalProperties") then obj
      else obj.add("additionalProperties", Json.False)
    withAdditional.add(
      "required",
      Json.fromValues(allKeys.map(Json.fromString))
    )
