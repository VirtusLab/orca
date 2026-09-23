package orca.settings

import munit.ScalaCheckSuite
import orca.StackSettings
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
    // `#` lines, so the parse result is exactly the Command entries' commands,
    // configured whenever a live (non-Demoted) entry exists.
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

  /** What parse must recover from a rendered entry list: the Command entries'
    * commands, appended per key in entry order, configured when any entry
    * renders a live line (Demoted renders a comment).
    */
  private def expectedSettings(
      entries: List[SettingsEntry]
  ): Option[StackSettings] =
    val live = entries.filter:
      case SettingsEntry.Demoted(_, _, _) => false
      case _                              => true
    Option.when(live.nonEmpty):
      live.foldLeft(StackSettings.empty): (acc, entry) =>
        entry match
          case SettingsEntry.Command(key, command, _) =>
            key match
              case StackKey.Format =>
                acc.copy(format = acc.format :+ command.value)
              case StackKey.Lint => acc.copy(lint = acc.lint :+ command.value)
              case StackKey.Test => acc.copy(test = acc.test :+ command.value)
          case _ => acc

  private val keyGen: Gen[StackKey] = Gen.oneOf(StackKey.values.toList)

  /** Printable ASCII, spaces, and occasional newlines — so `=`, mid-string `#`,
    * quotes, `$` and `&&` all occur — kept when [[StackCommand.from]] accepts
    * it.
    */
  private val commandGen: Gen[StackCommand] =
    val printable = Gen.choose(33.toChar, 126.toChar)
    val commandChar = Gen.frequency(
      9 -> printable,
      3 -> Gen.const(' '),
      1 -> Gen.const('\n')
    )
    for
      head <- printable.suchThat(_ != '#')
      tail <- Gen.listOf(commandChar)
      command <- StackCommand.from((head :: tail).mkString) match
        case Right(command) => Gen.const(command)
        case Left(_)        => Gen.fail
    yield command

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
      yield SettingsEntry.Demoted(key, command, reason),
      keyGen.map(SettingsEntry.Off(_))
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
