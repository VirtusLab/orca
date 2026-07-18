package orca.shell.ui

import java.io.{BufferedReader, PrintStream}
import scala.annotation.tailrec

/** Non-tty [[ShellUi]] fallback: a plain numbered-menu `readLine` loop, no
  * ANSI or raw mode. `in`/`out` are constructor parameters so this class is
  * testable without a real terminal; `ShellUi.make` wires them to
  * `System.in`/`System.out` when the tty gate fails.
  */
private[ui] final class NumberedUi(in: BufferedReader, out: PrintStream) extends ShellUi:

  def select[A](title: String, choices: List[Choice[A]], preselect: Option[A] = None): UiOutcome[A] =
    out.println(title)
    choices.zipWithIndex.foreach { case (choice, index) =>
      val marker = if preselect.contains(choice.value) then "*" else " "
      out.println(s"$marker${index + 1}. ${choice.renderedLabel}")
    }

    @tailrec def loop(): UiOutcome[A] =
      out.print("> ")
      out.flush()
      in.readLine() match
        case null => UiOutcome.Cancelled
        case line =>
          line.trim.toIntOption.flatMap(n => choices.lift(n - 1)) match
            case Some(choice) if choice.isEnabled => UiOutcome.Selected(choice.value)
            case _ =>
              out.println("Not a valid choice, try again.")
              loop()

    loop()

  def confirm(question: String, default: Boolean): UiOutcome[Boolean] =
    val hint = if default then "Y/n" else "y/N"

    @tailrec def loop(): UiOutcome[Boolean] =
      out.print(s"$question [$hint] ")
      out.flush()
      in.readLine() match
        case null => UiOutcome.Cancelled
        case line =>
          line.trim.toLowerCase match
            case ""          => UiOutcome.Selected(default)
            case "y" | "yes" => UiOutcome.Selected(true)
            case "n" | "no"  => UiOutcome.Selected(false)
            case _ =>
              out.println("Please answer y or n.")
              loop()

    loop()

  def input(prompt: String, default: Option[String] = None): UiOutcome[String] =
    val hint = default.fold("")(d => s" [$d]")
    out.print(s"$prompt$hint: ")
    out.flush()
    in.readLine() match
      case null => UiOutcome.Cancelled
      case line => UiOutcome.Selected(if line.isEmpty then default.getOrElse("") else line)
