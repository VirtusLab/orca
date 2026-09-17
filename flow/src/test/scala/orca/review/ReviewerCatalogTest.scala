package orca.review

import orca.{OrcaDir, OrcaFlowException}
import orca.discovery.Origin
import orca.testkit.{RepoRoot, TempDirs}

class ReviewerCatalogTest extends munit.FunSuite:

  /** A reviewer prompt file in `dir`, in the shipped frontmatter shape. */
  private def writeReviewer(
      dir: os.Path,
      slug: String,
      description: String = "checks the thing",
      files: Option[String] = None,
      body: String = "## Scope",
      newline: String = "\n"
  ): os.Path =
    val filesLine = files.map(f => s"files: $f$newline").getOrElse("")
    val path = dir / s"$slug.md"
    val lines =
      s"---${newline}description: $description$newline$filesLine---$newline"
    os.write(path, s"$lines$newline$body$newline", createFolders = true)
    path

  private def dirs(): (os.Path, os.Path) =
    val root = TempDirs.dir("orca-reviewers-")
    (root / "project", root / "global")

  private def named(reviewers: List[Reviewer]): List[String] =
    reviewers.map(_.name)

  test("this repository's own .orca/reviewers file loads"):
    // Through the same path production discovery uses, so renaming the tier
    // directory fails here rather than quietly finding nothing.
    val projectDir = OrcaDir.reviewersPath(RepoRoot.dir)
    val catalog =
      ReviewerCatalog.discover(projectDir, TempDirs.dir() / "absent")
    val reviewer = catalog.all
      .find(_.name == "orca")
      .getOrElse(fail(s"no reviewer named 'orca' in $projectDir"))
    assert(reviewer.appliesTo(List("Foo.scala")), reviewer.filePattern.toString)
    assert(
      reviewer.appliesTo(List("flows/x.sc")),
      reviewer.filePattern.toString
    )
    assert(
      !reviewer.appliesTo(List("README.md")),
      reviewer.filePattern.toString
    )
    // `orca` is not a shipped slug, so it is additive rather than a shadow.
    assertEquals(
      catalog.discovered.find(_.reviewer.name == "orca").map(_.shadows),
      Some(Nil)
    )

  test("project reviewers nothing ships are added to all and minimal, sorted"):
    val (project, global) = dirs()
    val _ = writeReviewer(project, "zeta")
    val _ = writeReviewer(project, "alpha")
    val catalog = ReviewerCatalog.discover(project, global)
    assertEquals(
      named(catalog.all),
      named(ReviewerPrompts.all) ++ List("alpha", "zeta")
    )
    assertEquals(
      named(catalog.minimal),
      named(ReviewerPrompts.minimal) ++ List("alpha", "zeta")
    )

  test("a project reviewer replaces the shipped one of the same slug in place"):
    val (project, global) = dirs()
    val _ = writeReviewer(
      project,
      "scala-fp",
      description = "our own scala rules",
      body = "## Our scope"
    )
    val catalog = ReviewerCatalog.discover(project, global)
    assertEquals(named(catalog.all), named(ReviewerPrompts.all))
    // `scala-fp` is outside `minimal`, so a shadow of it adds nothing there.
    assertEquals(named(catalog.minimal), named(ReviewerPrompts.minimal))
    val scalaFp = catalog.all.find(_.name == "scala-fp").get
    assertEquals(scalaFp.description, "our own scala rules")
    assertEquals(
      catalog.discovered.map(d => (d.reviewer.name, d.tier, d.shadows)),
      List(
        ("scala-fp", ReviewerFileTier.Project, List(Origin.BuiltIn))
      )
    )

  test("a filename differing only in case shadows the shipped slug"):
    // The picker resolves its reply case-insensitively, so a case variant that
    // stayed additive would run beside the reviewer it meant to replace.
    val (project, global) = dirs()
    val _ = writeReviewer(project, "Scala-FP", description = "ours")
    val catalog = ReviewerCatalog.discover(project, global)
    assertEquals(named(catalog.all), named(ReviewerPrompts.all))
    assertEquals(catalog.all.find(_.name == "scala-fp").get.description, "ours")
    assertEquals(
      catalog.discovered.map(_.shadows),
      List(List(Origin.BuiltIn))
    )

  test("a discovered file's body and files: become the prompt and pattern"):
    val (project, global) = dirs()
    val _ = writeReviewer(
      project,
      "orca",
      files = Some("""\.scala$"""),
      body = "## Our scope"
    )
    val orca = ReviewerCatalog.discover(project, global).all.last
    assertEquals(orca.systemPrompt, "## Our scope\n")
    assertEquals(orca.filePattern.map(_.regex), Some("""\.scala$"""))

  test("a project reviewer shadows the global one of the same slug"):
    val (project, global) = dirs()
    val _ = writeReviewer(project, "orca", description = "the project's")
    val _ = writeReviewer(global, "orca", description = "the user's")
    val catalog = ReviewerCatalog.discover(project, global)
    assertEquals(
      catalog.all.find(_.name == "orca").get.description,
      "the project's"
    )
    assertEquals(
      catalog.discovered.map(d => (d.tier, d.shadows)),
      List((ReviewerFileTier.Project, List(Origin.Global)))
    )

  test("a global reviewer is picked up when the project has none"):
    val (project, global) = dirs()
    val _ = writeReviewer(global, "orca", description = "the user's")
    val catalog = ReviewerCatalog.discover(project, global)
    assertEquals(named(catalog.all), named(ReviewerPrompts.all) :+ "orca")
    assertEquals(catalog.discovered.map(_.tier), List(ReviewerFileTier.Global))

  test("a CRLF reviewer file is read like any other"):
    val (project, global) = dirs()
    val _ = writeReviewer(project, "orca", newline = "\r\n")
    val catalog = ReviewerCatalog.discover(project, global)
    assertEquals(named(catalog.all), named(ReviewerPrompts.all) :+ "orca")

  test("a missing tier directory contributes nothing"):
    val (project, global) = dirs()
    val catalog = ReviewerCatalog.discover(project, global)
    assertEquals(catalog.all, ReviewerPrompts.all)
    assertEquals(catalog.discovered, Nil)

  test("a file that is not .md is ignored"):
    val (project, global) = dirs()
    os.write(project / "notes.txt", "not a reviewer", createFolders = true)
    assertEquals(ReviewerCatalog.discover(project, global).discovered, Nil)

  test("README.md and _-prefixed files sit in the directory as documents"):
    val (project, global) = dirs()
    val doc = "# Reviewers\n\nDrop a reviewer prompt here.\n"
    os.write(project / "README.md", doc, createFolders = true)
    os.write(project / "_draft.md", doc, createFolders = true)
    assertEquals(ReviewerCatalog.discover(project, global).discovered, Nil)

  test("any other .md with no frontmatter aborts, not silently skipped"):
    // Forgetting the frontmatter block is the likely authoring mistake; the
    // reviewer would otherwise vanish from the roster with nothing said.
    val (project, global) = dirs()
    os.write(
      project / "orca.md",
      "## Scope\n\nCheck things.\n",
      createFolders = true
    )
    val e =
      intercept[OrcaFlowException](ReviewerCatalog.discover(project, global))
    assert(e.getMessage.contains("has no frontmatter block"), e.getMessage)

  test(
    "a symlink and a duplicate slug are reported alongside a malformed file"
  ):
    val (project, global) = dirs()
    val _ = writeReviewer(project, "alpha", description = "")
    val _ = writeReviewer(project, "zeta")
    val _ = writeReviewer(project, "Zeta")
    os.symlink(project / "linked.md", writeReviewer(global, "elsewhere"))
    val e =
      intercept[OrcaFlowException](ReviewerCatalog.discover(project, global))
    assert(e.getMessage.contains("alpha"), e.getMessage)
    assert(e.getMessage.contains("'zeta' is claimed"), e.getMessage)
    assert(e.getMessage.contains("linked.md is a symlink"), e.getMessage)

  test("a dangling symlink in the global tier is reported, not dropped"):
    val (project, global) = dirs()
    os.makeDir.all(global)
    os.symlink(global / "orca.md", global / "gone.md")
    val e =
      intercept[OrcaFlowException](ReviewerCatalog.discover(project, global))
    assert(e.getMessage.contains("cannot be read"), e.getMessage)

  test("a symlinked .md in the project tier aborts, naming the link"):
    val (project, global) = dirs()
    val outside = writeReviewer(TempDirs.dir("orca-outside-"), "orca")
    os.makeDir.all(project)
    os.symlink(project / "orca.md", outside)
    assert(os.isLink(project / "orca.md"), "the fixture must be a symlink")
    val e =
      intercept[OrcaFlowException](ReviewerCatalog.discover(project, global))
    assert(e.getMessage.contains((project / "orca.md").toString), e.getMessage)

  test("a symlinked .md in the global tier is followed"):
    // The user's own config home, where a dotfiles manager links each file in.
    val (project, global) = dirs()
    val outside = writeReviewer(TempDirs.dir("orca-outside-"), "orca")
    os.makeDir.all(global)
    os.symlink(global / "orca.md", outside)
    assert(os.isLink(global / "orca.md"), "the fixture must be a symlink")
    val catalog = ReviewerCatalog.discover(project, global)
    assertEquals(named(catalog.all), named(ReviewerPrompts.all) :+ "orca")

  test("an unterminated frontmatter block aborts, not silently skipped"):
    val (project, global) = dirs()
    os.write(
      project / "orca.md",
      "---\ndescription: checks the thing\n\n## Scope\n",
      createFolders = true
    )
    val e =
      intercept[OrcaFlowException](ReviewerCatalog.discover(project, global))
    assert(
      e.getMessage.contains("block the parser could not read"),
      e.getMessage
    )

  test("two files claiming one slug in a tier abort"):
    val (project, global) = dirs()
    val _ = writeReviewer(project, "orca")
    val _ = writeReviewer(project, "Orca")
    val e =
      intercept[OrcaFlowException](ReviewerCatalog.discover(project, global))
    assert(e.getMessage.contains("Orca.md"), e.getMessage)
    assert(e.getMessage.contains("orca.md"), e.getMessage)

  test("a reviewer file with no description aborts, naming the file"):
    val (project, global) = dirs()
    os.write(
      project / "orca.md",
      "---\nname: orca\n---\n\nbody\n",
      createFolders = true
    )
    val e =
      intercept[OrcaFlowException](ReviewerCatalog.discover(project, global))
    assert(e.getMessage.contains((project / "orca.md").toString), e.getMessage)
    assert(e.getMessage.contains("description:"), e.getMessage)

  test("a whitespace-only description aborts like a missing one"):
    // Quoted, so the frontmatter parser's own trim doesn't collapse it.
    val (project, global) = dirs()
    val _ = writeReviewer(project, "orca", description = "\"  \"")
    val e =
      intercept[OrcaFlowException](ReviewerCatalog.discover(project, global))
    assert(e.getMessage.contains("description:"), e.getMessage)

  test("a reviewer file with no body aborts"):
    val (project, global) = dirs()
    val _ = writeReviewer(project, "orca", body = "   ")
    val e =
      intercept[OrcaFlowException](ReviewerCatalog.discover(project, global))
    assert(e.getMessage.contains("system prompt"), e.getMessage)

  test("a reviewer file with an unparseable files: regex aborts"):
    val (project, global) = dirs()
    val _ = writeReviewer(project, "orca", files = Some("""\.scala($"""))
    val e =
      intercept[OrcaFlowException](ReviewerCatalog.discover(project, global))
    assert(e.getMessage.contains("files:"), e.getMessage)

  test("builtIn is the shipped set with nothing discovered"):
    assertEquals(ReviewerCatalog.builtIn.all, ReviewerPrompts.all)
    assertEquals(ReviewerCatalog.builtIn.minimal, ReviewerPrompts.minimal)
    assertEquals(ReviewerCatalog.builtIn.describe, None)

  test("describe names each discovered reviewer, its tier and what it shadows"):
    val (project, global) = dirs()
    val _ = writeReviewer(project, "orca")
    val _ = writeReviewer(project, "scala-fp")
    assertEquals(
      ReviewerCatalog.discover(project, global).describe,
      Some(
        "discovered reviewers: orca (project); " +
          "scala-fp (project, shadows built-in)"
      )
    )
