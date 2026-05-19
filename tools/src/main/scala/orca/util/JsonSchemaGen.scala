package orca.util

import _root_.io.circe.syntax.EncoderOps
import sttp.apispec.circe.encoderSchema
import sttp.tapir.Schema
import sttp.tapir.docs.apispec.schema.TapirSchemaToJsonSchema

object JsonSchemaGen:
  def apply[O](using schema: Schema[O]): String =
    val jsonSchema =
      TapirSchemaToJsonSchema(schema, markOptionsAsNullable = true)
    jsonSchema.asJson.noSpaces
