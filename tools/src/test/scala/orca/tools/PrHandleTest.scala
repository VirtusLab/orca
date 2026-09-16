package orca.tools

class PrHandleTest extends munit.FunSuite:

  test("url points at the host the PR lives on"):
    assertEquals(
      PrHandle(
        host = "ghe.example.com",
        owner = "acme",
        repo = "widgets",
        number = 42
      ).url,
      "https://ghe.example.com/acme/widgets/pull/42"
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
