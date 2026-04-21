package orca

class DoneMarkerExtractorTest extends munit.FunSuite:
  private val payload = """{"x":1}"""

  test("extracts payload when marker is followed only by the JSON"):
    val raw =
      s"thinking out loud\n${DefaultPromptTemplate.DoneMarker}\n$payload"
    assertEquals(DoneMarkerExtractor.extract(raw), payload)

  test("extracts everything after a mid-text marker, trailing text included"):
    val raw =
      s"start\n${DefaultPromptTemplate.DoneMarker}\n$payload\ntrailing notes"
    assertEquals(DoneMarkerExtractor.extract(raw), s"$payload\ntrailing notes")

  test("uses the last marker when the marker appears multiple times"):
    val marker = DefaultPromptTemplate.DoneMarker
    val raw = s"prose $marker ignored $marker\n$payload"
    assertEquals(DoneMarkerExtractor.extract(raw), payload)

  test("throws OrcaFlowException when no marker is present"):
    intercept[OrcaFlowException]:
      DoneMarkerExtractor.extract("no marker here at all")
