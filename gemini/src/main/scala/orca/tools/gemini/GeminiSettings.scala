package orca.tools.gemini

import orca.backend.mcp.AskUserMcpServer
import orca.util.RawJson

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonValueCodec,
  readFromString,
  writeToString
}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

/** Registers the ephemeral `ask_user` MCP server with gemini for the lifetime
  * of one interactive conversation. Unlike codex (which takes an inline `-c
  * mcp_servers.orca.url=…` override), gemini only reads MCP server config from
  * `settings.json`, so we merge an entry into the project-local
  * `<workDir>/.gemini/settings.json` and restore the prior state when the
  * conversation finalises.
  *
  * Merge strategy preserves the user's file: unknown top-level keys and other
  * configured `mcpServers` ride through verbatim (held as [[RawJson]]). The
  * `allowedMcpServerNames` allowlist is only touched when it already exists —
  * introducing one where there was none would restrict gemini to ONLY orca and
  * hide the user's other servers.
  *
  * Two sharp edges (documented in ADR 0015): two interactive runs in the same
  * `workDir` race on this file, and a hard crash skips the restore, leaving a
  * stale `orca` entry behind. Restore is therefore best-effort, not
  * transactional.
  */
private[gemini] object GeminiSettings:

  private given objCodec: JsonValueCodec[Map[String, RawJson]] =
    JsonCodecMaker.make

  /** Merge the orca MCP server into `<workDir>/.gemini/settings.json` and
    * return an [[AutoCloseable]] that restores the prior state (original bytes,
    * or file removal if it didn't exist) on `close()`.
    */
  def register(workDir: os.Path, mcpUrl: String): AutoCloseable =
    val file = workDir / ".gemini" / "settings.json"
    val existed = os.exists(file)
    val original = if existed then os.read(file) else ""
    os.write.over(
      file,
      merge(if existed then original else "{}", mcpUrl),
      createFolders = true
    )
    () =>
      if existed then os.write.over(file, original)
      else if os.exists(file) then
        val _ = os.remove(file)

  /** Pure merge: inject `mcpServers.<ServerName>.httpUrl = mcpUrl` into the
    * top-level settings object, preserving every other key.
    */
  private[gemini] def merge(content: String, mcpUrl: String): String =
    val top = readFromString[Map[String, RawJson]](content)
    val servers = top
      .get("mcpServers")
      .map(raw => readFromString[Map[String, RawJson]](raw.value))
      .getOrElse(Map.empty)
    // Serialize the URL through the codec rather than interpolating it into a
    // raw JSON string, so a value containing `"` or `\` stays valid JSON.
    val orcaEntry = RawJson(
      writeToString(Map("httpUrl" -> mcpUrl))(using strMapCodec)
    )
    val mergedServers =
      servers + (AskUserMcpServer.ServerName -> orcaEntry)
    val withServers =
      top + ("mcpServers" -> RawJson(writeToString(mergedServers)))
    writeToString(withAllowlist(withServers))

  /** Append the orca server name to `allowedMcpServerNames`, but only when the
    * allowlist already exists (see the object scaladoc for why).
    */
  private def withAllowlist(
      top: Map[String, RawJson]
  ): Map[String, RawJson] =
    top.get("allowedMcpServerNames") match
      case None => top
      case Some(raw) =>
        val names = readFromString[List[String]](raw.value)(using listCodec)
        if names.contains(AskUserMcpServer.ServerName) then top
        else
          val updated = names :+ AskUserMcpServer.ServerName
          top + ("allowedMcpServerNames" -> RawJson(
            writeToString(updated)(using listCodec)
          ))

  private given listCodec: JsonValueCodec[List[String]] = JsonCodecMaker.make

  private given strMapCodec: JsonValueCodec[Map[String, String]] =
    JsonCodecMaker.make
