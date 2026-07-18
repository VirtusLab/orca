package orca.settings

/** The user-global config home: `$XDG_CONFIG_HOME/orca/`, defaulting to
  * `~/.config/orca/` (XDG Base Directory spec). A relative `XDG_CONFIG_HOME` is
  * ignored, as the spec mandates. A value `os.Path` rejects (e.g. `..` segments
  * that climb past the root) falls back like a relative one.
  */
private[orca] object GlobalSettings:
  private def configHome(
      env: String => Option[String],
      home: os.Path
  ): os.Path =
    env("XDG_CONFIG_HOME")
      // `os.Path` accepts only absolute paths, so a relative, empty, or
      // root-climbing value throws and falls back — no separate pre-filter.
      .flatMap(v => scala.util.Try(os.Path(v)).toOption)
      .getOrElse(home / ".config")

  /** `$XDG_CONFIG_HOME/orca/settings.properties` (ADR 0020). */
  def path(env: String => Option[String], home: os.Path): os.Path =
    configHome(env, home) / "orca" / "settings.properties"

  def default: os.Path = path(sys.env.get, os.home)

  /** `$XDG_CONFIG_HOME/orca/flows` — global-tier flow scripts (ADR 0021 §5),
    * sharing this object's config-home resolution.
    */
  def flowsPath(env: String => Option[String], home: os.Path): os.Path =
    configHome(env, home) / "orca" / "flows"

  def defaultFlows: os.Path = flowsPath(sys.env.get, os.home)
