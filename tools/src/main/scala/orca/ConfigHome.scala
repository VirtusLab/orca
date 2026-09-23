package orca

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
  def resolve(env: String => Option[String], home: os.Path): ConfigHome =
    ConfigHome(XdgDirs.configHome(env, home) / "orca")

  def default: ConfigHome = resolve(sys.env.get, os.home)
