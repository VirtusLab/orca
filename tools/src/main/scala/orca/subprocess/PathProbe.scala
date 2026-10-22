package orca.subprocess

import orca.effect.Processes

/** Whether a word resolves to a runnable command on PATH (ADR 0021 §4). Shared
  * by [[orca.lifecycle.StackDiscovery]]'s mechanical PATH check and the shell
  * wizard's harness-binary probe.
  */
private[orca] object PathProbe:

  /** Resolves `word` via `bash -c 'command -v -- "$1"' bash <word>`, run with
    * `cwd`. The word travels as an ARGUMENT (`"$1"`), never interpolated into
    * the script text, so shell metacharacters in `word` cannot execute here.
    * Exit 0 ⇒ resolvable (builtins included, same environment stage-time `bash
    * -c` inherits).
    */
  def resolves(procs: Processes, word: String, cwd: os.Path): Boolean =
    procs
      .call(Seq("bash", "-c", """command -v -- "$1"""", "bash", word), cwd)
      .exitCode == 0
