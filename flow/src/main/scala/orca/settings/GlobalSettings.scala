package orca.settings

/** The user-global settings file (ADR 0020):
  * `$XDG_CONFIG_HOME/orca/settings.properties`, defaulting to
  * `~/.config/orca/settings.properties` (XDG Base Directory spec). A relative
  * `XDG_CONFIG_HOME` is ignored, as the spec mandates.
  */
private[orca] object GlobalSettings:
  def path(env: String => Option[String], home: os.Path): os.Path =
    val configHome = env("XDG_CONFIG_HOME")
      .filter(v => v.nonEmpty && v.startsWith("/"))
      .map(os.Path(_))
      .getOrElse(home / ".config")
    configHome / "orca" / "settings.properties"

  def default: os.Path = path(sys.env.get, os.home)
