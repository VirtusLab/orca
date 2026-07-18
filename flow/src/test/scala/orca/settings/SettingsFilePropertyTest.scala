package orca.settings

import munit.ScalaCheckSuite
import orca.StackSettings
import orca.util.TextUtil
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** Laws of the [[SettingsFile]] parse/render pair. The example-based suite
  * ([[SettingsFileTest]]) pins exact byte shapes; these properties pin the
  * round-trip and totality contracts over generated inputs.
  */
class SettingsFilePropertyTest extends ScalaCheckSuite:

  property(
    "parse of render round-trips commands over the full free-text domain"
  ):
    // The round-trip law: comments, Unset reasons and Demoted commands/reasons
    // are arbitrary unicode (newlines, `#`, `=`, whitespace runs, hostile text
    // included) — render's sanitization must keep every free-text character on
    // `#` lines, so the parse result is exactly the Command entries' commands.
    forAll(entriesGen(arbitrary[String])): entries =>
      assertEquals(
        SettingsFile
          .parse(SettingsFile.render(entries), SettingsScope.Project)
          .map(_.stack),
        Right(expectedSettings(entries))
      )

  property("parse is total: any input yields Left or Right, never a throw"):
    forAll(parseInput): content =>
      val result = SettingsFile.parse(content, SettingsScope.Project)
      assert(result.isLeft || result.isRight)

  /** render's newline collapse followed by parse's value trim — the normal form
    * a command takes after one write/read cycle.
    */
  private def sanitize(command: String): String =
    TextUtil.collapseNewlines(command).trim

  /** What parse must recover from a rendered entry list: the Command entries'
    * sanitized commands, appended per key in entry order — Unset and Demoted
    * entries are invisible.
    */
  private def expectedSettings(entries: List[SettingsEntry]): StackSettings =
    entries.foldLeft(StackSettings.empty): (acc, entry) =>
      entry match
        case SettingsEntry.Command(key, command, _) =>
          SettingKey.fromRaw(key) match
            case Some(StackKey.Format) =>
              acc.copy(format = acc.format :+ sanitize(command))
            case Some(StackKey.Lint) =>
              acc.copy(lint = acc.lint :+ sanitize(command))
            case Some(StackKey.Test) =>
              acc.copy(test = acc.test :+ sanitize(command))
            case _ => fail(s"generator produced unknown key: $key")
        case SettingsEntry.Unset(_, _) | SettingsEntry.Demoted(_, _, _) => acc

  private val keyGen: Gen[String] =
    Gen.oneOf(StackKey.Format, StackKey.Lint, StackKey.Test).map(_.raw)

  /** A command within the domain [[SettingsEntry.Command]] documents (non-blank
    * after render's collapse, not `#`-leading): a non-`#` printable first char,
    * then printable ASCII, spaces, and occasional newlines — so `=`, mid-string
    * `#`, quotes, `$` and `&&` all occur, and the collapse law is exercised.
    */
  private val commandGen: Gen[String] =
    val printable = Gen.choose(33.toChar, 126.toChar)
    val commandChar = Gen.frequency(
      9 -> printable,
      3 -> Gen.const(' '),
      1 -> Gen.const('\n')
    )
    for
      head <- printable.suchThat(_ != '#')
      tail <- Gen.listOf(commandChar)
    yield (head :: tail).mkString

  private def entriesGen(freeText: Gen[String]): Gen[List[SettingsEntry]] =
    val entry = Gen.oneOf(
      for
        key <- keyGen
        command <- commandGen
        comment <- Gen.option(freeText)
      yield SettingsEntry.Command(key, command, comment),
      for
        key <- keyGen
        reason <- freeText
      yield SettingsEntry.Unset(key, reason),
      for
        key <- keyGen
        command <- freeText
        reason <- freeText
      yield SettingsEntry.Demoted(key, command, reason)
    )
    Gen.listOf(entry)

  /** Arbitrary strings, plus rendered files with an arbitrary string spliced in
    * at a random point — inputs shaped almost like a valid file.
    */
  private val parseInput: Gen[String] =
    val mutatedRender =
      for
        entries <- entriesGen(arbitrary[String])
        garbage <- arbitrary[String]
        rendered = SettingsFile.render(entries)
        at <- Gen.choose(0, rendered.length)
      yield rendered.take(at) + garbage + rendered.drop(at)
    Gen.oneOf(arbitrary[String], mutatedRender)
