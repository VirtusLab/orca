package orca.tools

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReaderException,
  readFromString,
  writeToString
}
import orca.agents.given
import orca.testkit.prHandle

class PrHandleTest extends munit.FunSuite:

  test("url points at the host the PR lives on"):
    assertEquals(
      PrHandle.from("ghe.example.com", "acme", "widgets", 42).map(_.url),
      Right("https://ghe.example.com/acme/widgets/pull/42")
    )

  test("from refuses a host carrying userinfo"):
    assert(PrHandle.from("github.com@evil.example", "acme", "w", 1).isLeft)

  test("the codec round-trips a handle"):
    val pr = prHandle("https://ghe.example.com/acme/widgets/pull/42")
    assertEquals(readFromString[PrHandle](writeToString(pr)), pr)

  test("from refuses a host with an empty label"):
    assert(PrHandle.from("a..b", "acme", "widgets", 1).isLeft)

  test("from refuses a non-positive PR number"):
    assert(PrHandle.from("github.com", "acme", "widgets", 0).isLeft)

  test("the codec refuses a recorded URL with trailing text"):
    // A stage record is read back from the committed progress log, so it is
    // validated as strictly as a URL parsed from gh.
    intercept[JsonReaderException](
      readFromString[PrHandle](
        "\"https://github.com/acme/widgets/pull/42?x=1\""
      )
    )

  test("fromUrl rejects a host carrying userinfo"):
    // `https://github.com@evil.example/...` reads as github.com but resolves
    // elsewhere, and `url` would render it back to the user as a link.
    assert(
      PrHandle
        .fromUrl("https://github.com@evil.example/acme/widgets/pull/42")
        .isEmpty
    )

  test("fromUrl rejects an http URL, which url cannot render back"):
    assert(
      PrHandle.fromUrl("http://ghe.example.com/acme/widgets/pull/42").isEmpty
    )

  test("fromUrl refuses a port, which gh's --hostname could not take"):
    // A portless handle would render a `url` pointing at a different server.
    assert(
      PrHandle
        .fromUrl("https://ghe.example.com:8443/acme/widgets/pull/42")
        .isEmpty
    )

  test("fromUrl gives no handle for a number too large for an Int"):
    assert(
      PrHandle
        .fromUrl("https://github.com/acme/widgets/pull/99999999999")
        .isEmpty
    )

  test("fromUrl rejects an owner outside GitHub's name charset"):
    // Owner and repo are spliced into `gh api` paths, so a `..` segment must
    // not parse — the same rule `IssueHandle` applies.
    assert(PrHandle.fromUrl("https://github.com/../widgets/pull/42").isEmpty)
