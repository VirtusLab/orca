package orca.tools.gemini

import orca.OrcaFlowException
import orca.backend.mcp.{AskUserMcpServer, McpHost}
import orca.util.RawJson

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonValueCodec,
  readFromString,
  writeToString
}
import com.github.plokhotnyuk.jsoniter_scala.macros.{
  CodecMakerConfig,
  JsonCodecMaker
}

import scala.util.Try

/** Registers the ephemeral `ask_user` MCP server with gemini for the lifetime
  * of one interactive conversation. gemini reads MCP server config only from
  * `settings.json`, so an `mcpServers.orca` entry is merged into the
  * project-local `<workDir>/.gemini/settings.json` and removed when the turn
  * ends.
  *
  * The merge preserves the user's file: other top-level keys and other
  * `mcpServers` entries ride through verbatim. gemini intersects the
  * `mcp.allowed` lists of all settings scopes, so a user who sets one must list
  * `orca` in it.
  *
  * An entry left by a hard crash is removed by the next interactive run in the
  * same `workDir`; until then other gemini runs there see a dead `orca` server.
  * Two interactive runs in one `workDir` race on the file (ADR 0015).
  *
  * The modified file sits in the user's tree for the whole turn, so a commit
  * the agent itself makes mid-turn includes it. Orca's own stage commits run
  * after the turn's restore.
  */
private[gemini] object GeminiSettings:

  private given objCodec: JsonValueCodec[Map[String, RawJson]] =
    JsonCodecMaker.make

  /** One `mcpServers.<name>` entry. jsoniter emits fields in declaration order,
    * so this serialises as `{"httpUrl":…,"timeout":…}`. Decoding rejects any
    * other field, so only an entry of exactly this shape parses.
    */
  private case class OrcaServerEntry(httpUrl: String, timeout: Long)
  private given entryCodec: JsonValueCodec[OrcaServerEntry] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(false))

  /** `timeout` (ms) mirrors [[orca.backend.mcp.AskUserMcpServer.ToolTimeout]]
    * (rendered as ms/sec across backends; keep in sync). Without it gemini
    * falls back to a shorter per-server default, giving up on `ask_user`
    * mid-answer and firing a duplicate question.
    */
  private val TimeoutMillis: Long = AskUserMcpServer.ToolTimeout.toMillis

  /** Merge the orca MCP server into `<workDir>/.gemini/settings.json` and
    * return an [[AutoCloseable]] that restores the prior state on `close()`:
    * the original bytes, or no file if there was none. A stale entry from an
    * earlier run is dropped from that prior state, and a file holding nothing
    * else counts as none. A `.gemini` directory created here or by that earlier
    * run is removed too, unless something else was put in it.
    *
    * Throws [[OrcaFlowException]] when `.gemini` or `settings.json` is a
    * symlink, before touching either.
    */
  def register(workDir: os.Path, mcpUrl: String): AutoCloseable =
    val dir = workDir / ".gemini"
    val file = dir / "settings.json"
    refuseSymlink(dir)
    refuseSymlink(file)
    val dirExisted = os.exists(dir)
    val fileExisted = os.exists(file)
    val prior = if fileExisted then withoutStaleOrca(os.read(file)) else None
    // A settings file holding only a stale entry means orca created `.gemini`.
    val orcaCreatedDir = !dirExisted || (fileExisted && prior.isEmpty)
    os.write.over(
      file,
      withOrca(prior.getOrElse("{}"), mcpUrl),
      createFolders = true
    )
    () =>
      prior match
        case Some(content) => os.write.over(file, content)
        case None          => os.remove(file): Unit
      if orcaCreatedDir && os.exists(dir) && os.list(dir).isEmpty then
        os.remove(dir): Unit

  /** A committed `.gemini` symlink would redirect the write outside the working
    * tree, e.g. into the user's global `~/.gemini`. `os.isLink` does not follow
    * links, so each path component is checked on its own.
    */
  private def refuseSymlink(path: os.Path): Unit =
    if os.isLink(path) then
      throw new OrcaFlowException(
        s"$path is a symlink — refusing to register orca's ask_user MCP " +
          "server through it. Replace it with a regular file or directory, or " +
          "run the gemini turn autonomously."
      )

  /** Inject `mcpServers.<ServerName> = {httpUrl, timeout}` into the top-level
    * settings object, preserving every other key.
    */
  private[gemini] def withOrca(content: String, mcpUrl: String): String =
    // Serialized through a typed codec, not interpolated, so a URL containing
    // `"` or `\` stays valid JSON.
    val entry = RawJson(writeToString(OrcaServerEntry(mcpUrl, TimeoutMillis)))
    val top = readFromString[Map[String, RawJson]](content)
    val servers = mcpServers(top) + (AskUserMcpServer.ServerName -> entry)
    writeToString(top + ("mcpServers" -> RawJson(writeToString(servers))))

  /** The settings without an `orca` entry that [[withOrca]] wrote; `None` when
    * nothing else is left. The content is returned verbatim when there is no
    * such entry, and re-serialized compactly otherwise. An `orca` entry of any
    * other shape is the user's own and is kept.
    */
  private[gemini] def withoutStaleOrca(content: String): Option[String] =
    val top = readFromString[Map[String, RawJson]](content)
    val servers = mcpServers(top)
    if !servers.get(AskUserMcpServer.ServerName).exists(isOrcaEntry) then
      Some(content)
    else
      val rest = servers - AskUserMcpServer.ServerName
      val stripped =
        if rest.isEmpty then top - "mcpServers"
        else top + ("mcpServers" -> RawJson(writeToString(rest)))
      Option.when(stripped.nonEmpty)(writeToString(stripped))

  private def mcpServers(top: Map[String, RawJson]): Map[String, RawJson] =
    top
      .get("mcpServers")
      .map(raw => readFromString[Map[String, RawJson]](raw.value))
      .getOrElse(Map.empty)

  /** Whether `raw` has the shape [[withOrca]] writes, pointing at an
    * [[McpHost]]. The timeout's value is not checked, so an entry from an orca
    * with a different timeout matches.
    */
  private def isOrcaEntry(raw: RawJson): Boolean =
    Try(readFromString[OrcaServerEntry](raw.value)).toOption
      .exists(entry => McpHost.isHostUrl(entry.httpUrl))
