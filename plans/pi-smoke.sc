#!/usr/bin/env -S scala-cli shebang -S 3
//> using dep "org.virtuslab::orca:0.0.8+4-d118161b+20260606-1239-SNAPSHOT"
//> using jvm 21

/** Minimal Pi-backed flow.
  *
  * Requires `pi` on PATH and authenticated/configured for the model/provider
  * you want to use. Orca drives Pi through `pi --mode rpc` and reuses the
  * stable session across calls when you pass the same `session` value.
  *
  * ```bash
  * scala-cli run pi-smoke.sc -- "Inspect this repository and summarize it"
  * ```
  */

import orca.{*, given}

case class RepoSummary(summary: String, nextSteps: List[String]) derives JsonData

flow(OrcaArgs(args)):
  val session = pi.newSession

  stage("Free-form Pi turn"):
    val _ = pi.autonomous.run(userPrompt, session)

  stage("Structured Pi turn"):
    val (_, summary) = pi.resultAs[RepoSummary].autonomous.run(
      s"Summarize the outcome of the previous work for: $userPrompt",
      session
    )
    fs.write(
      ".orca/pi-summary.md",
      s"${summary.summary}\n\n" + summary.nextSteps.map(s => s"- $s").mkString("\n")
    )
