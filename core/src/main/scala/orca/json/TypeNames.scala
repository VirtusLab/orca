package orca.json

import scala.quoted.*

/** A type's schema name: its fully qualified name plus those of its type
  * arguments, recursively. A parameterless enum case is `<enum>.<case>`;
  * Magnolia names it `<package>.<case>`, which JSON Schema keys don't show.
  */
private[json] object TypeNames:
  inline def schemaName[A]: (String, List[String]) = ${ schemaNameImpl[A] }

  private def schemaNameImpl[A: Type](using
      Quotes
  ): Expr[(String, List[String])] =
    import quotes.reflect.*
    // A parameterless enum case's type symbol is the enum itself.
    def symbolOf(t: TypeRepr): Symbol = t.dealias match
      case ref: TermRef => ref.termSymbol
      case other        => other.typeSymbol
    def full(t: TypeRepr): String = symbolOf(t).fullName.replace("$", "")
    def args(t: TypeRepr): List[TypeRepr] = t.dealias match
      case AppliedType(_, as) => as.flatMap(a => a :: args(a))
      case _                  => Nil
    val t = TypeRepr.of[A]
    Expr((full(t), args(t).map(full)))
