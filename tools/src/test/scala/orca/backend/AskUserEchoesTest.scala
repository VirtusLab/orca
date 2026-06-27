package orca.backend

class AskUserEchoesTest extends munit.FunSuite:

  test("consume returns true once for a suppressed id, then false"):
    val echoes = new AskUserEchoes
    echoes.suppress("a")
    assert(echoes.consume("a"), "first consume of a suppressed id is true")
    assert(!echoes.consume("a"), "the id is forgotten after one consume")

  test("consume is false for an id that was never suppressed"):
    val echoes = new AskUserEchoes
    assert(!echoes.consume("missing"))

  test("ids are tracked independently"):
    val echoes = new AskUserEchoes
    echoes.suppress("a")
    echoes.suppress("b")
    assert(echoes.consume("b"))
    assert(echoes.consume("a"))
