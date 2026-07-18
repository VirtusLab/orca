package orca.shell.flows

import orca.OrcaDir
import ox.tap

/** Where a built-in flow can be customized to (ADR 0021 §5) — excludes
  * `BuiltIn` itself, so [[FlowEditor.customizeTarget]] has no dead arm to
  * throw on.
  */
enum CustomizeTier:
  case Project, Global

/** Opens a flow in the user's editor (ADR 0021 §6). */
object FlowEditor:

  /** `$VISUAL` > `$EDITOR` > `"vi"` — the git/gh convention; no
    * orca-specific override.
    */
  def resolveEditor(env: String => Option[String]): String =
    env("VISUAL").orElse(env("EDITOR")).getOrElse("vi")

  /** The `sh -c '<editor> "$@"' <editor> <path>` argv (ADR 0021 §6): `editor`
    * is passed as `sh -c`'s `$0` and deliberately shell-interpreted (so
    * `code --wait` splits into command + argument), while `path` travels as
    * `$1` — `"$@"` expands it as a single word regardless of spaces.
    */
  def editArgv(editor: String, path: os.Path): Seq[String] =
    Seq("sh", "-c", s"""$editor "$$@"""", editor, path.toString)

  /** Spawns the editor inheriting the tty (stdin/stdout/stderr), so the
    * child can drive the terminal directly. The §2 subprocess obligations
    * (attribute restore) are the caller's responsibility — `Main` wraps this
    * call in `ChildTerminal.withChild`. Returns the exit code.
    */
  def edit(editor: String, path: os.Path): Int =
    os.proc(editArgv(editor, path))
      .call(stdin = os.Inherit, stdout = os.Inherit, stderr = os.Inherit, check = false)
      .exitCode

  /** Copies a built-in flow into `tier` (Project or Global) so it can be
    * edited without touching the immutable built-in cache — the copy
    * thereafter shadows the built-in (§5). Refuses with a message if a file
    * of the same name already exists at the destination, rather than
    * overwriting it.
    */
  def customizeTarget(
      flow: DiscoveredFlow,
      tier: CustomizeTier,
      workDir: os.Path,
      globalFlows: os.Path
  ): Either[String, os.Path] =
    val targetDir = tier match
      case CustomizeTier.Project => OrcaDir.ensureFlows(workDir)
      case CustomizeTier.Global  => globalFlows.tap(os.makeDir.all(_))
    val target = targetDir / flow.name
    if os.exists(target) then Left(s"$target already exists — refusing to overwrite it")
    else
      os.copy(flow.path, target)
      Right(target)
