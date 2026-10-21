package orca.json

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReaderException,
  readFromString
}

case class WithList(xs: List[Int]) derives JsonData

class JsonDataTest extends munit.FunSuite:

  test("a missing collection field fails to decode"):
    intercept[JsonReaderException](readFromString[WithList]("{}")): Unit
