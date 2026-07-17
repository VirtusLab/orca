package orca.settings

import munit.FunSuite

class GlobalSettingsTest extends FunSuite:

  private val home = os.root / "home" / "u"

  test("path uses $XDG_CONFIG_HOME/orca/settings.properties when set"):
    assertEquals(
      GlobalSettings.path(Map("XDG_CONFIG_HOME" -> "/tmp/xdg").get, home),
      os.Path("/tmp/xdg") / "orca" / "settings.properties"
    )

  test("path falls back to ~/.config/orca/settings.properties when unset"):
    assertEquals(
      GlobalSettings.path(Map.empty.get, home),
      home / ".config" / "orca" / "settings.properties"
    )

  test("path falls back to ~/.config when XDG_CONFIG_HOME is relative"):
    assertEquals(
      GlobalSettings.path(Map("XDG_CONFIG_HOME" -> "rel/path").get, home),
      home / ".config" / "orca" / "settings.properties"
    )

  test("path falls back to ~/.config when XDG_CONFIG_HOME is empty"):
    assertEquals(
      GlobalSettings.path(Map("XDG_CONFIG_HOME" -> "").get, home),
      home / ".config" / "orca" / "settings.properties"
    )
