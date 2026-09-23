package orca.backend

class AskUserEchoesTest extends munit.FunSuite:

  test("a suppressed id is consumed once, then forgotten"):
    val consumed = AskUserEchoes.empty.suppress("a").consume("a")
    assertEquals(consumed, Some(AskUserEchoes.empty))

  test("consume is None for an id that was never suppressed"):
    assertEquals(AskUserEchoes.empty.consume("missing"), None)

  test("ids are tracked independently"):
    val echoes = AskUserEchoes.empty.suppress("a").suppress("b")
    assertEquals(echoes.consume("b"), Some(AskUserEchoes.empty.suppress("a")))
