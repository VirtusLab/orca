package orca.json

import language.experimental.captureChecking

import sttp.tapir.{FieldName, Schema, SchemaType}
import sttp.tapir.Schema.SName

import scala.compiletime.{constValue, erasedValue, summonFrom, summonInline}
import scala.deriving.Mirror

/** Schema derivation standing in for tapir's `Schema.derived` (Magnolia), whose
  * expansion fails capture checking. For products, tuples and sealed sums it
  * renders to the same JSON Schema, up to key order; tapir's annotations
  * (`@description` etc.) are not read. A field's type needs a `Schema` in
  * scope; a sum's case is derived here when it has none. Recursive types are
  * not supported.
  */
private[orca] object SchemaDerivation:

  inline def derived[A](using m: Mirror.Of[A]): Schema[A] =
    inline m match
      case p: Mirror.ProductOf[A] => product[A](p)
      case s: Mirror.SumOf[A]     => sum[A](s)

  private inline def product[A](p: Mirror.ProductOf[A]): Schema[A] =
    val fields = labels[p.MirroredElemLabels]
      .zip(elemSchemas[p.MirroredElemTypes])
      .zipWithIndex
      .map:
        case ((label, s), i) =>
          SchemaType.SProductField[A, Any](
            FieldName(label),
            s.asInstanceOf[Schema[Any]],
            a => Some(a.asInstanceOf[Product].productElement(i))
          )
    val schema = Schema[A](SchemaType.SProduct(fields), Some(name[A]))
    if name[A].fullName.startsWith("scala.Tuple") then schema.tuple else schema

  private inline def sum[A](s: Mirror.SumOf[A]): Schema[A] =
    val (leaves, choose) = cases[A](s)
    Schema[A](
      SchemaType.SCoproduct[A](leaves.distinct, None)(a =>
        Some(SchemaType.SchemaWithValue(choose(a).asInstanceOf[Schema[Any]], a))
      ),
      Some(name[A])
    )

  /** The schemas of `s`'s cases, a nested sum's flattened into them as Magnolia
    * does, and the one a value takes.
    */
  private inline def cases[A](
      s: Mirror.SumOf[A]
  ): (List[Schema[?]], A -> Schema[?]) =
    val each = caseList[s.MirroredElemTypes]
    (each.flatMap(_._1), a => each(s.ordinal(a))._2(a))

  private inline def caseList[T <: Tuple]
      : List[(List[Schema[?]], Any -> Schema[?])] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   =>
        val head: (List[Schema[?]], Any -> Schema[?]) = summonFrom:
          case m: Mirror.SumOf[`h`] =>
            val (leaves, choose) = cases[h](m)
            (leaves, a => choose(a.asInstanceOf[h]))
          case s: Schema[`h`]           => (List(s), _ => s)
          case m: Mirror.ProductOf[`h`] =>
            val s = product[h](m)
            (List(s), _ => s)
        head :: caseList[t]

  private inline def name[A]: SName =
    val (full, args) = TypeNames.schemaName[A]
    SName(full, args)

  // `constValueTuple(...).toList` doesn't type-check under capture checking.
  inline def labels[T <: Tuple]: List[String] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   => constValue[h].toString :: labels[t]

  private inline def elemSchemas[T <: Tuple]: List[Schema[?]] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   => summonInline[Schema[h]] :: elemSchemas[t]
