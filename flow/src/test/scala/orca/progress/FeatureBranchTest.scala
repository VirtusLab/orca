package orca.progress

import munit.FunSuite

class FeatureBranchTest extends FunSuite:

  test("resolve refuses the always-protected floor regardless of the set"):
    for protectedName <- List("main", "master", "MAIN", "Master") do
      assert(
        FeatureBranch.resolve(protectedName, Set.empty).isLeft,
        s"$protectedName must be refused"
      )

  test("resolve refuses the caller-supplied protected set, case-insensitively"):
    assertEquals(
      FeatureBranch.resolve("trunk", Set("trunk")),
      Left(ProtectedBranchRefused("trunk"))
    )
    assert(FeatureBranch.resolve("TRUNK", Set("trunk")).isLeft)
    assert(FeatureBranch.resolve("trunk", Set("Trunk")).isLeft)

  test("resolve accepts a normal slug not in the protected set"):
    val resolved = FeatureBranch.resolve("feat/do-the-thing", Set("trunk"))
    assert(resolved.isRight)
    assertEquals(resolved.map(_.value), Right("feat/do-the-thing"))

  test("resolve accepts a normal slug when the protected set is empty"):
    assert(FeatureBranch.resolve("add-foo", Set.empty).isRight)

  test(".value round-trips the original name for an accepted branch"):
    val Right(fb) =
      FeatureBranch.resolve("flow-1a2b3c4d", Set.empty): @unchecked
    assertEquals(fb.value, "flow-1a2b3c4d")
