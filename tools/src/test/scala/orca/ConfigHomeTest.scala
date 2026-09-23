package orca

import munit.FunSuite

class ConfigHomeTest extends FunSuite:

  private val home = os.root / "home" / "u"

  private def resolve(xdg: Option[String]): ConfigHome =
    ConfigHome.resolve(xdg.map("XDG_CONFIG_HOME" -> _).toMap.get, home)

  test("the root is orca under the XDG config home"):
    assertEquals(resolve(Some("/tmp/xdg")).root, os.Path("/tmp/xdg") / "orca")

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
