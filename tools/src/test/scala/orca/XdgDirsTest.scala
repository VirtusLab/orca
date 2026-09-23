package orca

import munit.FunSuite

class XdgDirsTest extends FunSuite:

  private val home = os.root / "home" / "u"

  private def env(variable: String, value: Option[String]) =
    value.map(variable -> _).toMap.get

  private def config(value: Option[String]): os.Path =
    XdgDirs.configHome(env("XDG_CONFIG_HOME", value), home)

  private def cache(value: Option[String]): os.Path =
    XdgDirs.cacheHome(env("XDG_CACHE_HOME", value), home)

  private val rootClimbing = "/a/b/../../../../../../c"

  test("configHome is XDG_CONFIG_HOME when set"):
    assertEquals(config(Some("/tmp/xdg")), os.Path("/tmp/xdg"))

  test("configHome falls back to ~/.config when XDG_CONFIG_HOME is unset"):
    assertEquals(config(None), home / ".config")

  test("configHome falls back to ~/.config when XDG_CONFIG_HOME is relative"):
    assertEquals(config(Some("rel/path")), home / ".config")

  test("configHome falls back to ~/.config when XDG_CONFIG_HOME is empty"):
    assertEquals(config(Some("")), home / ".config")

  test("configHome falls back to ~/.config when XDG_CONFIG_HOME climbs past /"):
    assertEquals(config(Some(rootClimbing)), home / ".config")

  test("cacheHome is XDG_CACHE_HOME when set"):
    assertEquals(cache(Some("/tmp/xdg")), os.Path("/tmp/xdg"))

  test("cacheHome falls back to ~/.cache when XDG_CACHE_HOME is unset"):
    assertEquals(cache(None), home / ".cache")
