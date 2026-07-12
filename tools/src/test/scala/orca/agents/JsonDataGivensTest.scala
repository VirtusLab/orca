package orca.agents

import com.github.plokhotnyuk.jsoniter_scala.core.{
  readFromString,
  writeToString
}
import munit.FunSuite

class JsonDataGivensTest extends FunSuite:

  private def roundTrip[A](value: A)(using jd: JsonData[A]): A =
    readFromString[A](writeToString(value)(using jd.codec))(using jd.codec)

  test("String round-trips"):
    assertEquals(roundTrip("hello world"), "hello world")

  test("Int round-trips"):
    assertEquals(roundTrip(42), 42)

  test("Long round-trips"):
    assertEquals(roundTrip(9876543210L), 9876543210L)

  test("Boolean round-trips true"):
    assertEquals(roundTrip(true), true)

  test("Boolean round-trips false"):
    assertEquals(roundTrip(false), false)

  test("Double round-trips"):
    assertEquals(roundTrip(3.14), 3.14)

  test("Unit round-trips"):
    assertEquals(roundTrip(()), ())

  test("Option[Unit] Some round-trips"):
    assertEquals(roundTrip(Some(()): Option[Unit]), Some(()))

  test("Option[Unit] None round-trips"):
    // The strict Unit decoder rejects `null`, so the Option codec's own
    // null→None handling is what produces None here (and Some(()) reads `{}`).
    assertEquals(roundTrip(None: Option[Unit]), None)

  test("Option[Int] Some round-trips"):
    assertEquals(roundTrip(Some(1): Option[Int]), Some(1))

  test("Option[Int] None round-trips"):
    assertEquals(roundTrip(None: Option[Int]), None)

  test("List[Int] round-trips"):
    assertEquals(roundTrip(List(1, 2, 3)), List(1, 2, 3))

  test("List[Int] empty round-trips"):
    assertEquals(roundTrip(List.empty[Int]), List.empty[Int])

  test("(String, Int) round-trips"):
    assertEquals(roundTrip(("hello", 42)), ("hello", 42))

  test("(String, Int, Boolean) round-trips"):
    assertEquals(roundTrip(("x", 1, true)), ("x", 1, true))

  test("Option[List[Int]] nested round-trips"):
    assertEquals(
      roundTrip(Some(List(1, 2)): Option[List[Int]]),
      Some(List(1, 2))
    )
