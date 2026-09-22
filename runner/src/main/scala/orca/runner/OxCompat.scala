package orca.runner

import ox.{NoEnclosingConcurrencyScope, OxUnsupervised, ResourceScope}

import scala.util.NotGiven

/** Stand-ins for Ox APIs that don't link from orca's Scala version. */
private[orca] object OxCompat:

  /** [[ox.resourceScope]], with the same compile-time check that no concurrency
    * scope is visible.
    *
    * Ox 1.0.7 is built with Scala 3.3, whose `NoEnclosingConcurrencyScope`
    * given returns `BoxedUnit`; Scala 3.9 call sites expect `void`, so calling
    * `ox.resourceScope` directly throws `NoSuchMethodError`. The evidence is
    * passed here without calling that given. Drop this once Ox's given links.
    */
  def resourceScope[T](f: ResourceScope ?=> T)(using
      NotGiven[OxUnsupervised]
  ): T =
    ox.resourceScope(f)(using ().asInstanceOf[NoEnclosingConcurrencyScope])
