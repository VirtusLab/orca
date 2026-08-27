package orca.runner.terminal

class TextTest extends munit.FunSuite:

  test("middleTruncate keeps a trailing segment longer than half the budget"):
    // The `/Main…` segment is 25 characters against a 29-character keep, so it
    // is what decides the split — an even one would cut the filename in half.
    val path = "/home/user/project/src/AbsolutelyLongName.scala"
    assertEquals(
      Text.middleTruncate(path, 30),
      "/hom…/AbsolutelyLongName.scala"
    )

  test("middleTruncate splits evenly when the trailing segment alone overruns"):
    // Nothing is left for the head if the whole segment is kept, so the head
    // wins back half the budget and the filename takes the loss.
    assertEquals(
      Text.middleTruncate(s"/a/${"x" * 40}", 20),
      s"/a/${"x" * 7}…${"x" * 9}"
    )
