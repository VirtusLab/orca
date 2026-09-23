package orca.progress

import munit.FunSuite

class FeatureBranchTest extends FunSuite:

  test("isSafeBranchRef accepts slug names and issue branches"):
    assert(FeatureBranch.isSafeBranchRef("add-foo"))
    assert(FeatureBranch.isSafeBranchRef("fix/issue-42"))
    assert(FeatureBranch.isSafeBranchRef("flow-1a2b3c4d"))

  test("isSafeBranchRef rejects empty, leading-dash, traversal, and spaces"):
    assert(!FeatureBranch.isSafeBranchRef(""))
    assert(!FeatureBranch.isSafeBranchRef("-x"))
    assert(!FeatureBranch.isSafeBranchRef("a/.."))
    assert(!FeatureBranch.isSafeBranchRef("a b"))
    assert(!FeatureBranch.isSafeBranchRef("Feat"))
    assert(!FeatureBranch.isSafeBranchRef("a/"))

  test(
    "isSafeReusedRef accepts mixed case and slashed names slugs would reject"
  ):
    assert(FeatureBranch.isSafeReusedRef("feature/JIRA-123"))
    assert(FeatureBranch.isSafeReusedRef("Feature-ABC"))
    assert(FeatureBranch.isSafeReusedRef("add-foo")) // a slug also passes

  test(
    "isSafeReusedRef rejects argv-injection and path-traversal shapes"
  ):
    assert(!FeatureBranch.isSafeReusedRef(""))
    assert(!FeatureBranch.isSafeReusedRef("-flag"))
    assert(!FeatureBranch.isSafeReusedRef("a..b"))
    assert(!FeatureBranch.isSafeReusedRef("a/../b"))
    assert(!FeatureBranch.isSafeReusedRef("a b"))
    assert(!FeatureBranch.isSafeReusedRef("x.lock"))
    assert(!FeatureBranch.isSafeReusedRef("a/"))
    assert(!FeatureBranch.isSafeReusedRef("/a"))
    assert(!FeatureBranch.isSafeReusedRef("a\tb"))

  test(
    "isSafeReusedRef rejects glob metacharacters (would DoS `git branch --list`)"
  ):
    assert(!FeatureBranch.isSafeReusedRef("*"))
    assert(!FeatureBranch.isSafeReusedRef("feature-*"))
    assert(!FeatureBranch.isSafeReusedRef("a?b"))
    assert(!FeatureBranch.isSafeReusedRef("[abc]"))
    assert(!FeatureBranch.isSafeReusedRef("a\\b"))

  test(
    "isSafeReusedRef rejects the literal pseudo-ref HEAD (never a real branch)"
  ):
    assert(!FeatureBranch.isSafeReusedRef("HEAD"))

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

  test("resolve refuses an unsafe ref shape, distinctly from a protected name"):
    assertEquals(
      FeatureBranch.resolve("Feat", Set.empty),
      Left(UnsafeBranchRefRefused("Feat"))
    )

  test("resolve passes an already-slugged, multi-segment name through"):
    assertEquals(
      FeatureBranch.resolve("fix/issue-42", Set("trunk")).map(_.value),
      Right("fix/issue-42")
    )
