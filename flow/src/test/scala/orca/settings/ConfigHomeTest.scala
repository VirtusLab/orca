package orca.settings

import munit.FunSuite

class ConfigHomeTest extends FunSuite:

  private val home = os.root / "home" / "u"

  private def resolve(xdg: Option[String]): ConfigHome =
    ConfigHome.resolve(xdg.map("XDG_CONFIG_HOME" -> _).toMap.get, home)

  test("the root is $XDG_CONFIG_HOME/orca when set"):
    assertEquals(resolve(Some("/tmp/xdg")).root, os.Path("/tmp/xdg") / "orca")

  test("the root falls back to ~/.config/orca when XDG_CONFIG_HOME is unset"):
    assertEquals(resolve(None).root, home / ".config" / "orca")

  test("the root falls back to ~/.config when XDG_CONFIG_HOME is relative"):
    assertEquals(resolve(Some("rel/path")).root, home / ".config" / "orca")

  test("the root falls back to ~/.config when XDG_CONFIG_HOME is empty"):
    assertEquals(resolve(Some("")).root, home / ".config" / "orca")

  test("the root falls back to ~/.config when XDG_CONFIG_HOME climbs past /"):
    assertEquals(
      resolve(Some("/a/b/../../../../../../c")).root,
      home / ".config" / "orca"
    )

  test("settings is settings.properties under the root"):
    assertEquals(
      resolve(Some("/tmp/xdg")).settings,
      os.Path("/tmp/xdg") / "orca" / "settings.properties"
    )

  test("flows is the flows directory under the root"):
    assertEquals(
      resolve(Some("/tmp/xdg")).flows,
      os.Path("/tmp/xdg") / "orca" / "flows"
    )

  test("reviewers is the reviewers directory under the root"):
    assertEquals(
      resolve(Some("/tmp/xdg")).reviewers,
      os.Path("/tmp/xdg") / "orca" / "reviewers"
    )
