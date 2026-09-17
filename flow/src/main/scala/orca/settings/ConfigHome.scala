package orca.settings

/** The user-global config home: `$XDG_CONFIG_HOME/orca/`, defaulting to
  * `~/.config/orca/` (XDG Base Directory spec). `root` is that directory; each
  * global tier lives directly under it, so one resolved home fixes all three.
  */
private[orca] case class ConfigHome(root: os.Path):
  /** `settings.properties` — the user-global agent keys (ADR 0020). */
  def settings: os.Path = root / "settings.properties"

  /** `flows/` — global-tier flow scripts (ADR 0021 §5). */
  def flows: os.Path = root / "flows"

  /** `reviewers/` — global-tier reviewer prompts (ADR 0023). */
  def reviewers: os.Path = root / "reviewers"

private[orca] object ConfigHome:
  /** A relative, empty, or root-climbing `XDG_CONFIG_HOME` is ignored and falls
    * back to `~/.config`, as the spec mandates.
    */
  def resolve(env: String => Option[String], home: os.Path): ConfigHome =
    val base = env("XDG_CONFIG_HOME")
      // `os.Path` accepts only absolute paths, so a relative, empty, or
      // root-climbing value throws and falls back — no separate pre-filter.
      .flatMap(v => scala.util.Try(os.Path(v)).toOption)
      .getOrElse(home / ".config")
    ConfigHome(base / "orca")

  def default: ConfigHome = resolve(sys.env.get, os.home)
