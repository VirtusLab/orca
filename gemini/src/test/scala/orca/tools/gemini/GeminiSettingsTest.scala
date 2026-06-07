package orca.tools.gemini

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

class GeminiSettingsTest extends munit.FunSuite:

  private given mapCodec
      : com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec[
        Map[String, orca.util.RawJson]
      ] = JsonCodecMaker.make

  private def settingsFile(workDir: os.Path): os.Path =
    workDir / ".gemini" / "settings.json"

  private def topLevel(content: String): Map[String, orca.util.RawJson] =
    readFromString[Map[String, orca.util.RawJson]](content)

  test(
    "register creates settings.json with the orca MCP server when none exists"
  ):
    val workDir = os.temp.dir()
    val _ = GeminiSettings.register(workDir, "http://127.0.0.1:9999/mcp")
    val file = settingsFile(workDir)
    assert(os.exists(file), "settings.json should be created")
    val keys = topLevel(os.read(file))
    assert(keys.contains("mcpServers"), s"expected mcpServers; got: $keys")
    val servers = topLevel(keys("mcpServers").value)
    assert(servers.contains("orca"), s"expected orca server; got: $servers")
    assert(servers("orca").value.contains("http://127.0.0.1:9999/mcp"))

  test("register does NOT add an allowlist when the user has none"):
    // allowedMcpServerNames is an allowlist; adding one where there was none
    // would restrict gemini to ONLY orca, hiding the user's other servers.
    val workDir = os.temp.dir()
    val _ = GeminiSettings.register(workDir, "http://x/mcp")
    val keys = topLevel(os.read(settingsFile(workDir)))
    assert(
      !keys.contains("allowedMcpServerNames"),
      s"must not introduce an allowlist; got: $keys"
    )

  test("close removes the file again when it did not exist before"):
    val workDir = os.temp.dir()
    val restore = GeminiSettings.register(workDir, "http://x/mcp")
    assert(os.exists(settingsFile(workDir)))
    restore.close()
    assert(
      !os.exists(settingsFile(workDir)),
      "a file we created should be removed on restore"
    )

  test("register preserves existing keys and restores exact bytes on close"):
    val workDir = os.temp.dir()
    val file = settingsFile(workDir)
    val original =
      """{"theme":"dark","mcpServers":{"github":{"httpUrl":"http://gh/mcp"}}}"""
    os.write(file, original, createFolders = true)

    val restore = GeminiSettings.register(workDir, "http://orca/mcp")
    val keys = topLevel(os.read(file))
    assertEquals(keys("theme").value, "\"dark\"")
    val servers = topLevel(keys("mcpServers").value)
    assert(servers.contains("github"), s"existing server lost; got: $servers")
    assert(servers.contains("orca"), s"orca not added; got: $servers")

    restore.close()
    assertEquals(
      os.read(file),
      original,
      "close must restore the original bytes verbatim"
    )

  test("register appends orca to an existing allowlist"):
    val workDir = os.temp.dir()
    val file = settingsFile(workDir)
    os.write(
      file,
      """{"allowedMcpServerNames":["github"]}""",
      createFolders = true
    )
    val _ = GeminiSettings.register(workDir, "http://orca/mcp")
    val allowlist = topLevel(os.read(file))("allowedMcpServerNames").value
    assert(allowlist.contains("github"), s"existing entry lost: $allowlist")
    assert(allowlist.contains("orca"), s"orca not allowlisted: $allowlist")
