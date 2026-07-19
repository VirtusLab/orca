package orca.shell.flows

import org.jline.builtins.SyntaxHighlighter

/** Renders a flow's source for the "view" menu item (ADR 0021 §6). */
private[shell] object FlowViewer:
  private val nanorcResource = "classpath:/orca/shell/scala.nanorc"

  /** `source` unchanged on a non-tty (piped output stays byte-identical);
    * Scala-syntax-highlighted, ANSI-escaped otherwise. Builds a fresh
    * [[SyntaxHighlighter]] per call — it carries mutable multi-line-rule state,
    * so reuse across calls would leak between unrelated renders.
    *
    * The single-argument `SyntaxHighlighter.build(String)` is used rather than
    * the two-argument `build(nanorcUrl, syntaxName)` sketched in the task
    * brief: jar-verified (jline 3.30.15), no such overload exists — the only
    * classpath-URL overload takes just the nanorc URL, resolving via
    * `Class.getResourceAsStream` on everything after `"classpath:"`.
    */
  def render(source: String, tty: Boolean): String =
    if !tty then source
    else SyntaxHighlighter.build(nanorcResource).highlight(source).toAnsi()
