package orca.settings

/** The user-global settings file (ADR 0020):
  * `$XDG_CONFIG_HOME/orca/settings.properties`, defaulting to
  * `~/.config/orca/settings.properties` (XDG Base Directory spec). A relative
  * `XDG_CONFIG_HOME` is ignored, as the spec mandates. A value `os.Path`
  * rejects (e.g. `..` segments that climb past the root) falls back like a
  * relative one.
  */
private[orca] object GlobalSettings:
  def path(env: String => Option[String], home: os.Path): os.Path =
    val configHome = env("XDG_CONFIG_HOME")
      // `os.Path` accepts only absolute paths, so a relative, empty, or
      // root-climbing value throws and falls back — no separate pre-filter.
      .flatMap(v => scala.util.Try(os.Path(v)).toOption)
      .getOrElse(home / ".config")
    configHome / "orca" / "settings.properties"

  def default: os.Path = path(sys.env.get, os.home)
