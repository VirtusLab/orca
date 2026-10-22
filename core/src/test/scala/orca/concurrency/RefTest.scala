package orca.concurrency

import ox.{fork, supervised}

class RefTest extends munit.FunSuite:

  private val Forks = 64

  private def concurrently[R](body: => R): List[R] =
    supervised:
      (1 to Forks).map(_ => fork(body)).toList.map(_.join())

  test("modify hands its result to exactly one of racing callers"):
    val ref = Ref("")
    val claimed = concurrently(
      ref.modify(s => (s + "k", !s.contains("k")))
    )
    assertEquals(claimed.count(identity), 1)

  test("update applies every racing update"):
    val ref = Ref(0)
    concurrently(ref.update(_ + 1)): Unit
    assertEquals(ref.get, Forks)
