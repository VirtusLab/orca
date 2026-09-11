package orca.tools

class PrHandleTest extends munit.FunSuite:

  test("url points at the host the PR lives on"):
    assertEquals(
      PrHandle("ghe.example.com", "acme", "widgets", 42).url,
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
