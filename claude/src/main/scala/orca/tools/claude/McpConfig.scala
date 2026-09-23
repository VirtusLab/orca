package orca.tools.claude

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonValueCodec,
  writeToString
}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

import scala.concurrent.duration.FiniteDuration

/** Renders the JSON of claude's `--mcp-config` file. */
private[claude] object McpConfig:

  /** One host-served MCP server; `timeout` is claude's per-tool-call limit. */
  final case class HttpServer(url: String, timeout: FiniteDuration)

  /** One `mcpServers.<name>` entry; `timeout` is in milliseconds. */
  private case class Entry(`type`: String, url: String, timeout: Long)
  private case class File(mcpServers: Map[String, Entry])
  private given JsonValueCodec[File] = JsonCodecMaker.make

  def render(servers: Map[String, HttpServer]): String =
    writeToString(
      File(
        servers.map((name, s) =>
          name -> Entry("http", s.url, s.timeout.toMillis)
        )
      )
    )
