package orca.runner

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import orca.StackSettings
import orca.agents.JsonData
import orca.settings.{SettingsEntry, SettingsFile}
import orca.testkit.TempDirs

class StackDiscoveryTest extends munit.FunSuite:

  test(
    "a representative always-both envelope shape decodes under the strict codec"
  ):
    // The strict output schema requires both keys on every task, so agents
    // emit "commands": [] and "unsetReason": null where a side doesn't apply
    // — this pins that the strict jsoniter codec accepts exactly that shape,
    // including the single-property "result" envelope.
    val json =
      """{"result":
        | {"format": {"commands": [{"command": "acme style --write",
        |     "evidencePath": "acme.build",
        |     "evidenceNote": "style ships with the acme toolchain, zero-config; CI also runs it in .ci/check.yml line 12"}],
        |   "unsetReason": null},
        |  "lint":   {"commands": [{"command": "acme compile --include-tests",
        |     "evidencePath": "acme.build",
        |     "evidenceNote": null}],
        |   "unsetReason": null},
        |  "test":   {"commands": [], "unsetReason": "no test directory or CI test step found"}}}""".stripMargin
    val decoded = readFromString[StackDiscoveryReply](json)(using
      summon[JsonData[StackDiscoveryReply]].codec
    )
    assertEquals(
      decoded.result,
      StackDiscoveryResult(
        format = DiscoveredTask(
          commands = List(
            DiscoveredCommand(
              "acme style --write",
              "acme.build",
              Some(
                "style ships with the acme toolchain, zero-config; CI also runs it in .ci/check.yml line 12"
              )
            )
          ),
          unsetReason = None
        ),
        lint = DiscoveredTask(
          commands = List(
            DiscoveredCommand("acme compile --include-tests", "acme.build")
          ),
          unsetReason = None
        ),
        test = DiscoveredTask(
          commands = Nil,
          unsetReason = Some("no test directory or CI test step found")
        )
      )
    )

  /** Checks that pass everything — the assembly tests inject failures
    * explicitly where a scenario needs them.
    */
  private val allResolvable: String => Option[String] = _ => None
  private val allEvidenceExists: String => Boolean = _ => true

  test("toEntries: surviving commands become Command entries, in task order"):
    val result = StackDiscoveryResult(
      format = DiscoveredTask(commands =
        List(
          DiscoveredCommand(
            "cargo fmt",
            "Cargo.toml",
            Some("rustfmt ships with the toolchain")
          ),
          DiscoveredCommand("pnpm exec prettier --write .", "package.json")
        )
      ),
      lint = DiscoveredTask(commands =
        List(DiscoveredCommand("cargo check --tests", "Cargo.toml"))
      ),
      test = DiscoveredTask(commands =
        List(DiscoveredCommand("cargo test", "Cargo.toml"))
      )
    )
    val (entries, settings) =
      StackDiscovery.toEntries(result, allResolvable, allEvidenceExists)
    assertEquals(
      entries,
      List(
        SettingsEntry.Command(
          "format",
          "cargo fmt",
          Some("Cargo.toml; rustfmt ships with the toolchain")
        ),
        SettingsEntry.Command(
          "format",
          "pnpm exec prettier --write .",
          Some("package.json")
        ),
        SettingsEntry
          .Command("lint", "cargo check --tests", Some("Cargo.toml")),
        SettingsEntry.Command("test", "cargo test", Some("Cargo.toml"))
      )
    )
    assertEquals(
      settings,
      StackSettings(
        format = List("cargo fmt", "pnpm exec prettier --write ."),
        lint = List("cargo check --tests"),
        test = List("cargo test")
      )
    )

  test("toEntries: an unresolvable command demotes with the check's reason"):
    val result = StackDiscoveryResult(
      format = DiscoveredTask(),
      lint = DiscoveredTask(commands =
        List(DiscoveredCommand("just check", "justfile"))
      ),
      test = DiscoveredTask()
    )
    val (entries, settings) = StackDiscovery.toEntries(
      result,
      unresolvedReason = c =>
        if c.startsWith("just") then Some("just: not found on PATH") else None,
      evidenceExists = allEvidenceExists
    )
    // Exact list: the all-demoted lint task contributes only its demoted line
    // — no contradictory Unset line alongside it.
    assertEquals(
      entries,
      List(
        SettingsEntry.Unset("format", "no evidence found"),
        SettingsEntry.Demoted("lint", "just check", "just: not found on PATH"),
        SettingsEntry.Unset("test", "no evidence found")
      )
    )
    assertEquals(settings.lint, Nil, "a demoted command must not join settings")

  test("toEntries: a missing evidence file demotes, naming the file"):
    val result = StackDiscoveryResult(
      format = DiscoveredTask(commands =
        List(DiscoveredCommand("cargo fmt", "Cargo.toml"))
      ),
      lint = DiscoveredTask(),
      test = DiscoveredTask()
    )
    val (entries, settings) = StackDiscovery.toEntries(
      result,
      unresolvedReason = allResolvable,
      evidenceExists = _ => false
    )
    // Exact list: the all-demoted format task contributes only its demoted
    // line — no contradictory Unset line alongside it.
    assertEquals(
      entries,
      List(
        SettingsEntry.Demoted(
          "format",
          "cargo fmt",
          "evidence file Cargo.toml not found"
        ),
        SettingsEntry.Unset("lint", "no evidence found"),
        SettingsEntry.Unset("test", "no evidence found")
      )
    )
    assertEquals(settings.format, Nil)

  test("toEntries: a blank evidence citation demotes"):
    val result = StackDiscoveryResult(
      format =
        DiscoveredTask(commands = List(DiscoveredCommand("cargo fmt", "  "))),
      lint = DiscoveredTask(),
      test = DiscoveredTask()
    )
    // `allEvidenceExists` would accept the citation, so only the blank guard
    // can demote here.
    val (entries, settings) =
      StackDiscovery.toEntries(result, allResolvable, allEvidenceExists)
    assertEquals(
      entries.head,
      SettingsEntry.Demoted("format", "cargo fmt", "no evidence file cited")
    )
    assertEquals(settings.format, Nil)

  test("toEntries: a task with no commands and a reason becomes Unset(reason)"):
    val result = StackDiscoveryResult(
      format = DiscoveredTask(),
      lint = DiscoveredTask(),
      test = DiscoveredTask(unsetReason = Some("no test directory found"))
    )
    val (entries, _) =
      StackDiscovery.toEntries(result, allResolvable, allEvidenceExists)
    assert(
      entries.contains(SettingsEntry.Unset("test", "no test directory found")),
      s"expected the agent's unset reason to carry through, got: $entries"
    )

  test(
    "toEntries: a command containing a newline is sanitized at assembly — settings and rendered file agree"
  ):
    val result = StackDiscoveryResult(
      format = DiscoveredTask(commands =
        List(DiscoveredCommand("cargo fmt --all\n  --check", "Cargo.toml"))
      ),
      lint = DiscoveredTask(),
      test = DiscoveredTask()
    )
    val (entries, settings) =
      StackDiscovery.toEntries(result, allResolvable, allEvidenceExists)
    assertEquals(settings.format, List("cargo fmt --all --check"))
    // Parsing the rendered file back yields the exact settings the run got —
    // the first run executes what the written file carries.
    assertEquals(
      SettingsFile.parse(SettingsFile.render(entries)),
      Right(settings)
    )

  test("toEntries: a task with neither commands nor a reason gets a stock one"):
    val result = StackDiscoveryResult(
      format = DiscoveredTask(),
      lint = DiscoveredTask(),
      test = DiscoveredTask()
    )
    val (entries, settings) =
      StackDiscovery.toEntries(result, allResolvable, allEvidenceExists)
    assertEquals(
      entries,
      List(
        SettingsEntry.Unset("format", "no evidence found"),
        SettingsEntry.Unset("lint", "no evidence found"),
        SettingsEntry.Unset("test", "no evidence found")
      )
    )
    assertEquals(settings, StackSettings.empty)

  // --- mechanical checks -----------------------------------------------------

  test("unresolvedReason: a plain word on PATH resolves"):
    assertEquals(
      StackDiscovery.unresolvedReason("echo hello", TempDirs.dir()),
      None
    )

  test("unresolvedReason: an unknown word demotes, naming it"):
    assertEquals(
      StackDiscovery
        .unresolvedReason("definitely-not-a-cmd-xyz --flag", TempDirs.dir()),
      Some("definitely-not-a-cmd-xyz: not found on PATH")
    )

  test("unresolvedReason: a leading VAR= assignment token is stripped"):
    assertEquals(
      StackDiscovery.unresolvedReason("FOO=bar echo hi", TempDirs.dir()),
      None
    )

  test("unresolvedReason: two leading VAR= assignment tokens are stripped"):
    assertEquals(
      StackDiscovery
        .unresolvedReason("FOO=bar B_2=x echo hi", TempDirs.dir()),
      None
    )

  test("unresolvedReason: an all-assignments (empty) command demotes"):
    assertEquals(
      StackDiscovery.unresolvedReason("FOO=bar", TempDirs.dir()),
      Some("empty command")
    )

  test(
    "unresolvedReason: shell metacharacters are never interpreted — no side effects"
  ):
    // The word goes to `command -v -- "$1"` as an ARGUMENT, never spliced into
    // the script text: `echo; touch marker` must not create `marker`, whatever
    // the resolution verdict on the (unresolvable) word `echo;` is.
    val dir = TempDirs.dir()
    val result = StackDiscovery.unresolvedReason("echo; touch marker", dir)
    assert(
      !os.exists(dir / "marker"),
      "the probe must not execute any part of the command"
    )
    assertEquals(result, Some("echo;: not found on PATH"))

  test("unresolvedReason: an executable ./script.sh in the repo resolves"):
    val dir = TempDirs.dir()
    os.write(dir / "script.sh", "#!/bin/sh\n")
    os.perms.set(dir / "script.sh", "rwxr-xr-x")
    assertEquals(
      StackDiscovery.unresolvedReason("./script.sh --check", dir),
      None
    )

  test("unresolvedReason: an absent ./script.sh demotes"):
    assertEquals(
      StackDiscovery.unresolvedReason("./missing.sh", TempDirs.dir()),
      Some("./missing.sh: not found on PATH")
    )

  test("evidenceExists: a present repo-relative file is accepted"):
    val dir = TempDirs.dir()
    os.write(dir / "Cargo.toml", "[package]\n")
    assert(StackDiscovery.evidenceExists("Cargo.toml", dir))

  test("evidenceExists: a missing repo-relative file is rejected"):
    assert(!StackDiscovery.evidenceExists("Cargo.toml", TempDirs.dir()))

  test("evidenceExists: ../ traversal and absolute paths are rejected"):
    val dir = TempDirs.dir()
    os.write(dir / "real.txt", "x")
    assert(!StackDiscovery.evidenceExists("../escape.txt", dir))
    assert(!StackDiscovery.evidenceExists("/etc/passwd", dir))

end StackDiscoveryTest
