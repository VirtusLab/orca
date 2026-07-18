package orca

import mainargs.{Flag, ParserForClass, arg}

/** Parsed command-line arguments for the `orca` entry point. */
case class OrcaArgs(
    @arg(positional = true, doc = "task description")
    userPrompt: String = "",
    @arg(doc = "verbose logging")
    verbose: Flag = Flag()
)

object OrcaArgs:
  given ParserForClass[OrcaArgs] = ParserForClass[OrcaArgs]

  /** Parse the given argv or return a human-readable error. */
  def parse(args: Seq[String]): Either[String, OrcaArgs] =
    summon[ParserForClass[OrcaArgs]].constructEither(args.toList)

  /** Overload for scala-cli flow scripts, whose top-level `args` is
    * `Array[String]`. Throws `OrcaFlowException` on a parse failure.
    */
  def apply(args: Array[String]): OrcaArgs = from(args.toSeq)

  def from(args: Seq[String]): OrcaArgs =
    parse(args) match
      case Right(parsed)  => parsed
      case Left(errorMsg) => throw OrcaFlowException(errorMsg)
