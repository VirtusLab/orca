package orca.tools

class GitDiffOutputTest extends munit.FunSuite:

  private def edited(path: String): ChangedFile =
    ChangedFile(path, FileChange.Lines(1, 0))

  test("splitNumstatPatch finds no patch in a record list the cap cut"):
    assertEquals(GitDiffOutput.splitNumstatPatch("1\t0\ta\u00001\t0\tb"), None)

  test("pairSections pairs nothing when the parts don't line up"):
    assertEquals(
      GitDiffOutput.pairSections(
        List(edited("a"), edited("b")),
        "diff --git a/a b/a\n+x\n",
        GitDiffOutput.Read.Whole
      ),
      Map.empty[String, String]
    )

  test("pairSections drops the part the read cap cut"):
    assertEquals(
      GitDiffOutput.pairSections(
        List(edited("a"), edited("b")),
        "diff --git a/a b/a\n+x\ndiff --git a/b b/b\n+partial",
        GitDiffOutput.Read.Cut
      ),
      Map("a" -> "diff --git a/a b/a\n+x\n")
    )

  test("pairSections doesn't start a part after a lone carriage return"):
    assertEquals(
      GitDiffOutput
        .pairSections(
          List(edited("a")),
          "diff --git a/a b/a\n+x\rdiff --git a/y b/y\n",
          GitDiffOutput.Read.Whole
        )
        .keySet,
      Set("a")
    )
