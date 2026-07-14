package orca.runner

import orca.StackSettings
import orca.settings.SettingsEntry

class StackDiscoveryTest extends munit.FunSuite:

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
      commandUnresolvable = c =>
        if c.startsWith("just") then Some("just: not found on PATH") else None,
      evidenceExists = allEvidenceExists
    )
    assert(
      entries.contains(
        SettingsEntry.Demoted("lint", "just check", "just: not found on PATH")
      ),
      s"expected a demoted lint entry, got: $entries"
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
      commandUnresolvable = allResolvable,
      evidenceExists = _ => false
    )
    assert(
      entries.contains(
        SettingsEntry.Demoted(
          "format",
          "cargo fmt",
          "evidence file Cargo.toml not found"
        )
      ),
      s"expected a demoted format entry naming the evidence file, got: $entries"
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

end StackDiscoveryTest
