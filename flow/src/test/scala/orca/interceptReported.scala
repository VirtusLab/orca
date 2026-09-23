package orca

import munit.{Assertions, Location}

import scala.reflect.ClassTag

/** The failure `body` lets escape a stage or `fail`, unwrapped from its
  * [[ReportedFailure]]; fails the test unless it is an `E`.
  */
def interceptReported[E <: Throwable: ClassTag](body: => Any)(using
    Location
): E =
  Assertions.intercept[ReportedFailure](body).cause match
    case e: E  => e
    case other => Assertions.fail(s"unexpected reported failure: $other")
