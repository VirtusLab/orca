package orca.concurrency

class RefTest extends munit.FunSuite:

  test("modify reruns f on the value another writer set meanwhile"):
    val ref = Ref(0)
    var calls = 0
    val result = ref.modify: s =>
      calls += 1
      if calls == 1 then ref.set(10)
      (s + 1, s)
    assertEquals((result, ref.get, calls), (10, 11, 2))

  test("update replaces the value with f of it"):
    val ref = Ref(1)
    ref.update(_ + 1)
    assertEquals(ref.get, 2)
