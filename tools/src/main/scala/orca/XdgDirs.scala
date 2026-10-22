package orca

import orca.safe.prelude.{absolutePath, given}

/** The per-user base directories of the XDG Base Directory spec. A relative,
  * empty, or root-climbing variable is ignored and falls back like an unset
  * one, as the spec mandates.
  */
private[orca] object XdgDirs:
  /** `$XDG_CONFIG_HOME`, defaulting to `home / ".config"`. */
  def configHome(env: String => Option[String], home: os.Path): os.Path =
    resolve(env, "XDG_CONFIG_HOME", home / ".config")

  /** `$XDG_CACHE_HOME`, defaulting to `home / ".cache"`. */
  def cacheHome(env: String => Option[String], home: os.Path): os.Path =
    resolve(env, "XDG_CACHE_HOME", home / ".cache")

  private def resolve(
      env: String => Option[String],
      variable: String,
      fallback: os.Path
  ): os.Path =
    env(variable).flatMap(absolutePath).getOrElse(fallback)
