package orca.subprocess

/** Outcome of [[orca.effect.Processes.callCapped]]. `out` is a prefix of what
  * the child wrote whenever `truncated` — and, being a prefix of bytes rather
  * than of characters, can end in a replacement character where the cut split
  * one.
  */
case class CappedResult(
    exitCode: Int,
    out: String,
    truncated: Boolean,
    err: String
)
