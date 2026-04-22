package orca.runner.terminal

import mainargs.Flag

class OrcaArgsTest extends munit.FunSuite:

  test("parses an empty argv into defaults (empty prompt, verbose off)"):
    assertEquals(OrcaArgs.parse(Nil), Right(OrcaArgs("", Flag())))

  test("a single positional argument becomes userPrompt"):
    val result = OrcaArgs
      .parse(Seq("implement feature X"))
      .toOption
      .getOrElse(fail("expected successful parse"))
    assertEquals(result.userPrompt, "implement feature X")
    assertEquals(result.verbose.value, false)

  test("--verbose flag sets verbose = true"):
    val result = OrcaArgs
      .parse(Seq("--verbose", "do the thing"))
      .toOption
      .getOrElse(fail("expected successful parse"))
    assertEquals(result.userPrompt, "do the thing")
    assertEquals(result.verbose.value, true)

  test("unknown flags yield a Left with an error message"):
    OrcaArgs.parse(Seq("--nonexistent")) match
      case Left(msg) => assert(msg.nonEmpty)
      case Right(r)  => fail(s"expected parse failure, got $r")
