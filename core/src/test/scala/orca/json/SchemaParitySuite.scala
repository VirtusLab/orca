package orca.json

import io.circe.Json
import io.circe.syntax.EncoderOps
import sttp.apispec.circe.encoderSchema
import sttp.tapir.Schema
import sttp.tapir.docs.apispec.schema.TapirSchemaToJsonSchema

import scala.compiletime.{constValueTuple, erasedValue, summonFrom}
import scala.deriving.Mirror

/** Pins schemas derived by [[SchemaDerivation]] (directly or through
  * `JsonData`) to what tapir's own derivation produces, rendered as JSON
  * Schema: a `parity[A]` test per type, comparing the `Schema[A]` in scope.
  *
  * Key order is not compared: `$defs` come out in a different order, as
  * Magnolia names a parameterless enum case `<package>.<case>`, dropping the
  * enum, and orders cases by that name.
  */
trait SchemaParitySuite extends munit.FunSuite:

  inline def parity[A](using schema: Schema[A], m: Mirror.Of[A]): Unit =
    val name = schema.name.fold("?")(_.fullName)
    val actual = SchemaParitySuite.render(schema)
    val expected = SchemaParitySuite.render(SchemaParitySuite.tapirSchema[A])
    test(s"$name: schema matches tapir's derivation"):
      assertEquals(actual, expected)

object SchemaParitySuite:

  def render[A](schema: Schema[A]): Json =
    TapirSchemaToJsonSchema(schema, markOptionsAsNullable = true).asJson

  /** The schema tapir derives as `JsonData` would ask it to: a string enum for
    * a sum of parameterless cases, `Schema.derived` otherwise.
    */
  inline def tapirSchema[A](using m: Mirror.Of[A]): Schema[A] =
    inline m match
      case s: Mirror.SumOf[A] =>
        inline if allSingletons[s.MirroredElemTypes] then
          val labels = constValueTuple[s.MirroredElemLabels].toList
            .map(_.toString)
            .toVector
          Schema.derivedEnumeration[A](encode = Some(a => labels(s.ordinal(a))))
        else Schema.derived[A]
      case _ => Schema.derived[A]

  private inline def allSingletons[T <: Tuple]: Boolean =
    inline erasedValue[T] match
      case _: EmptyTuple => true
      case _: (h *: t)   =>
        summonFrom:
          case _: ValueOf[`h`] => allSingletons[t]
          case _               => false
