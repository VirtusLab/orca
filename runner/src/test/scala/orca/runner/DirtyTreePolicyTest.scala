package orca.runner

import java.util.concurrent.atomic.AtomicInteger

/** The dirty-tree decision on its own: no repository, no terminal. `ask` and
  * `tty` are counted, since "was the user asked at all" is half of what these
  * cells promise.
  */
class DirtyTreePolicyTest extends munit.FunSuite:

  private def fresh(dirtyCount: Int): DirtyTreeFacts =
    DirtyTreeFacts(
      ownLogPresent = false,
      skipBranch = false,
      keepChanges = false,
      dirtyCount = dirtyCount
    )

  test("off a terminal, a fresh dirty run stashes without asking"):
    val asks = new AtomicInteger(0)
    val choice = DirtyTreePolicy.decide(
      fresh(dirtyCount = 3),
      tty = () => false,
      ask = () => { val _ = asks.incrementAndGet(); DirtyTreeChoice.Keep }
    )
    assertEquals(choice, DirtyTreeChoice.Stash)
    assertEquals(asks.get(), 0, "a headless run must never prompt")

  test(
    "an existing progress log stashes without asking, or even probing for a terminal"
  ):
    val probes = new AtomicInteger(0)
    val asks = new AtomicInteger(0)
    val choice = DirtyTreePolicy.decide(
      fresh(dirtyCount = 2).copy(ownLogPresent = true),
      tty = () => { val _ = probes.incrementAndGet(); true },
      ask = () => { val _ = asks.incrementAndGet(); DirtyTreeChoice.Keep }
    )
    assertEquals(choice, DirtyTreeChoice.Stash)
    assertEquals(asks.get(), 0, "an existing log is already decided")
    assertEquals(probes.get(), 0, "a decided case must not spawn the tty probe")

  test("--skip-branch keeps a fresh dirty tree without asking"):
    val asks = new AtomicInteger(0)
    val choice = DirtyTreePolicy.decide(
      fresh(dirtyCount = 1).copy(skipBranch = true),
      tty = () => true,
      ask = () => { val _ = asks.incrementAndGet(); DirtyTreeChoice.Stash }
    )
    assertEquals(choice, DirtyTreeChoice.Keep)
    assertEquals(asks.get(), 0, "the flag already answered the question")

  test("--keep-changes keeps a fresh dirty tree without asking"):
    val asks = new AtomicInteger(0)
    val choice = DirtyTreePolicy.decide(
      fresh(dirtyCount = 1).copy(keepChanges = true),
      tty = () => true,
      ask = () => { val _ = asks.incrementAndGet(); DirtyTreeChoice.Stash }
    )
    assertEquals(choice, DirtyTreeChoice.Keep)
    assertEquals(asks.get(), 0, "the flag already answered the question")

  test("a clean tree is never asked about"):
    val asks = new AtomicInteger(0)
    val choice = DirtyTreePolicy.decide(
      fresh(dirtyCount = 0),
      tty = () => true,
      ask = () => { val _ = asks.incrementAndGet(); DirtyTreeChoice.Abort }
    )
    assertEquals(choice, DirtyTreeChoice.Stash)
    assertEquals(asks.get(), 0, "there is nothing to decide about")

  test("on a terminal, a fresh dirty run returns the user's answer"):
    val answers =
      List(DirtyTreeChoice.Keep, DirtyTreeChoice.Stash, DirtyTreeChoice.Abort)
    answers.foreach: answer =>
      assertEquals(
        DirtyTreePolicy
          .decide(fresh(dirtyCount = 1), tty = () => true, () => answer),
        answer
      )

  test("the menu reads 2 as keep and 3 as abort"):
    assertEquals(DirtyTreePolicy.parse(Some("2")), DirtyTreeChoice.Keep)
    assertEquals(DirtyTreePolicy.parse(Some("3")), DirtyTreeChoice.Abort)

  test("an empty, unrecognized, or absent answer falls back to stashing"):
    assertEquals(DirtyTreePolicy.parse(Some("")), DirtyTreeChoice.Stash)
    assertEquals(DirtyTreePolicy.parse(Some("yes")), DirtyTreeChoice.Stash)
    assertEquals(DirtyTreePolicy.parse(None), DirtyTreeChoice.Stash)

  test("the production prompt asks on stderr, reads stdin, keeps stdout clean"):
    val out = new java.io.ByteArrayOutputStream()
    val err = new java.io.ByteArrayOutputStream()
    val choice = Console.withIn(new java.io.StringReader("2\n")):
      Console.withOut(out):
        Console.withErr(err):
          DirtyTreePolicy.promptOnStderr(dirtyCount = 2)
    assertEquals(choice, DirtyTreeChoice.Keep)
    val menu = err.toString("UTF-8")
    assert(menu.contains("2 uncommitted/untracked file(s)"), menu)
    assert(
      menu.contains("1)") && menu.contains("2)") && menu.contains("3)"),
      s"all three options must be offered: $menu"
    )
    assertEquals(out.toString("UTF-8"), "", "stdout must stay clean")

  test("the production prompt stashes when stdin is at EOF"):
    val choice = Console.withIn(new java.io.StringReader("")):
      Console.withErr(new java.io.ByteArrayOutputStream()):
        DirtyTreePolicy.promptOnStderr(dirtyCount = 1)
    assertEquals(choice, DirtyTreeChoice.Stash)
