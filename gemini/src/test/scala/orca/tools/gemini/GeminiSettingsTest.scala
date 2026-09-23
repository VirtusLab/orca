package orca.tools.gemini

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import orca.OrcaFlowException
import orca.testkit.TempDirs

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
    val workDir = TempDirs.dir()
    val _ = GeminiSettings.register(workDir, "http://127.0.0.1:9999/mcp")
    val file = settingsFile(workDir)
    assert(os.exists(file), "settings.json should be created")
    val keys = topLevel(os.read(file))
    assert(keys.contains("mcpServers"), s"expected mcpServers; got: $keys")
    val servers = topLevel(keys("mcpServers").value)
    assert(servers.contains("orca"), s"expected orca server; got: $servers")
    assert(servers("orca").value.contains("http://127.0.0.1:9999/mcp"))

  test("withOrca escapes a URL containing JSON metacharacters"):
    // The URL is serialized through a codec, not interpolated, so a `"` or `\`
    // can't break out of the string and produce invalid JSON.
    val merged = GeminiSettings.withOrca("{}", """http://h/"x\y""")
    val servers = topLevel(topLevel(merged)("mcpServers").value)
    val orcaEntry = topLevel(servers("orca").value)
    val httpUrl = readFromString[String](orcaEntry("httpUrl").value)(using
      JsonCodecMaker.make[String]
    )
    assertEquals(httpUrl, """http://h/"x\y""")

  test("withOrca sets the orca server's timeout to the shared ToolTimeout"):
    // 3 600 000 ms == 1h == AskUserMcpServer.ToolTimeout. Without it gemini's
    // own MCP client default undercuts the shared budget and fires a duplicate
    // question mid-answer.
    val merged = GeminiSettings.withOrca("{}", "http://x/mcp")
    val servers = topLevel(topLevel(merged)("mcpServers").value)
    assertEquals(
      servers("orca").value,
      """{"httpUrl":"http://x/mcp","timeout":3600000}"""
    )

  test("close removes a .gemini directory it created"):
    val workDir = TempDirs.dir()
    val restore = GeminiSettings.register(workDir, "http://x/mcp")
    assert(os.exists(settingsFile(workDir)))
    restore.close()
    assert(!os.exists(workDir / ".gemini"))

  test("close keeps a .gemini directory it created that gained other files"):
    val workDir = TempDirs.dir()
    val restore = GeminiSettings.register(workDir, "http://x/mcp")
    val other = workDir / ".gemini" / "other.json"
    os.write(other, "{}")
    restore.close()
    assert(!os.exists(settingsFile(workDir)))
    assertEquals(os.read(other), "{}")

  test("close keeps a pre-existing .gemini directory"):
    val workDir = TempDirs.dir()
    os.makeDir(workDir / ".gemini")
    val restore = GeminiSettings.register(workDir, "http://x/mcp")
    restore.close()
    assert(!os.exists(settingsFile(workDir)))
    assert(os.isDir(workDir / ".gemini"))

  test("register preserves existing keys and restores exact bytes on close"):
    val workDir = TempDirs.dir()
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

  test("register drops a stale orca entry left by a crashed run"):
    val workDir = TempDirs.dir()
    val file = settingsFile(workDir)
    val original = """{"theme":"dark"}"""
    os.write(
      file,
      GeminiSettings.withOrca(original, "http://127.0.0.1:1/mcp"),
      createFolders = true
    )
    GeminiSettings.register(workDir, "http://orca/mcp").close()
    assertEquals(topLevel(os.read(file)).keySet, Set("theme"))

  test("close removes a .gemini directory that held only a stale orca entry"):
    val workDir = TempDirs.dir()
    val file = settingsFile(workDir)
    os.write(
      file,
      GeminiSettings.withOrca("{}", "http://127.0.0.1:1/mcp"),
      createFolders = true
    )
    GeminiSettings.register(workDir, "http://orca/mcp").close()
    assert(!os.exists(workDir / ".gemini"))

  test("register keeps a user's own orca entry and restores it on close"):
    val workDir = TempDirs.dir()
    val file = settingsFile(workDir)
    val original = """{"mcpServers":{"orca":{"command":"my-orca"}}}"""
    os.write(file, original, createFolders = true)
    GeminiSettings.register(workDir, "http://orca/mcp").close()
    assertEquals(os.read(file), original)

  test("register refuses a symlinked .gemini directory"):
    val workDir = TempDirs.dir()
    val target = TempDirs.dir()
    os.symlink(workDir / ".gemini", target)
    val _ = intercept[OrcaFlowException](
      GeminiSettings.register(workDir, "http://orca/mcp")
    )
    assertEquals(os.list(target), Seq.empty)

  test("register refuses a symlinked settings.json"):
    val workDir = TempDirs.dir()
    val target = TempDirs.dir() / "settings.json"
    os.write(target, "{}")
    os.makeDir(workDir / ".gemini")
    os.symlink(settingsFile(workDir), target)
    val _ = intercept[OrcaFlowException](
      GeminiSettings.register(workDir, "http://orca/mcp")
    )
    assertEquals(os.read(target), "{}")
