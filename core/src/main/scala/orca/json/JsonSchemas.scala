package orca.json

import language.experimental.captureChecking

import caps.assumeSafe
import io.circe.{Json, JsonObject}
import io.circe.syntax.EncoderOps
import sttp.apispec.circe.encoderSchema
import sttp.tapir.Schema
import sttp.tapir.docs.apispec.schema.TapirSchemaToJsonSchema

import scala.util.boundary
import scala.util.boundary.break

/** JSON-Schema strings in OpenAI's "strict" structured-output dialect (used by
  * both `codex exec --output-schema` and `claude --json-schema`):
  *
  *   - every object node carries `additionalProperties: false`;
  *   - every object's `required` array lists every key in `properties`.
  *
  * Optional fields and fields with Scala-side defaults are marked nullable, so
  * requiring them is safe — the agent emits `null` or an empty list rather than
  * omitting.
  *
  * A `Map[String, _]` field has no fixed key set, so it can't be expressed in
  * the strict dialect; such a schema is refused, with a message naming the fix.
  */
@assumeSafe
private[orca] object JsonSchemas:

  /** `schema` in the strict dialect, or why it has no strict form. */
  def strict[O](using schema: Schema[O]): Either[String, String] =
    val jsonSchema =
      TapirSchemaToJsonSchema(schema, markOptionsAsNullable = true)
    // Tapir stamps `$schema: .../draft/2020-12/schema` on its output; the
    // claude CLI (observed on 2.1.207) has no 2020-12 meta-schema registered
    // and rejects any schema declaring that dialect ("no schema with key or ref
    // ...") before the turn starts. No consumer needs the declaration, so strip
    // it.
    toOpenAiStrict(jsonSchema.asJson).map(
      _.mapObject(_.remove("$schema")).noSpaces
    )

  /** Every object subtree of `json` with the two strict-mode constraints
    * injected, or why one cannot carry them.
    */
  def toOpenAiStrict(json: Json): Either[String, Json] =
    boundary(Right(strictly(json)))

  private def strictly(json: Json)(using
      boundary.Label[Either[String, Json]]
  ): Json =
    json.fold(
      jsonNull = json,
      jsonBoolean = _ => json,
      jsonNumber = _ => json,
      jsonString = _ => json,
      jsonArray = arr => Json.fromValues(arr.map(strictly)),
      jsonObject = obj => Json.fromJsonObject(transformObject(obj))
    )

  private def transformObject(obj: JsonObject)(using
      boundary.Label[Either[String, Json]]
  ): JsonObject =
    val recursed = JsonObject.fromIterable(
      obj.toIterable.map((k, v) => k -> strictly(v))
    )
    if isMapShaped(recursed) then break(Left(MapShapedMessage))
    if isObjectSchemaNode(recursed) then addStrictConstraints(recursed)
    else recursed

  /** Tapir renders a `Map[String, T]` field as an object node whose
    * `additionalProperties` is `T`'s own sub-schema (no `properties` key — the
    * keys are unbounded). Overwriting it with `false` would silently discard
    * the value-type constraint.
    */
  private def isMapShaped(obj: JsonObject): Boolean =
    obj("additionalProperties").exists(!_.isBoolean)

  private val MapShapedMessage: String =
    "resultAs[O]: output schema has a Map[String, _]-shaped field " +
      "(an object with a value-type `additionalProperties` schema), " +
      "which OpenAI's strict structured-output mode doesn't support. " +
      "Model it as a List of key/value case classes instead."

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
