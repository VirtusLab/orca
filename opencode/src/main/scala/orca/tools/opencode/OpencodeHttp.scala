package orca.tools.opencode

import orca.backend.StreamSource

/** The OpenCode HTTP server as the backend consumes it (ADR 0014): a JSON POST
  * (session create, prompt, ask_user/permission replies) and the `GET /event`
  * SSE stream as a [[StreamSource]].
  *
  * A small seam so [[OpencodeConversation]] and [[OpencodeBackend]] are
  * testable without a live server — implemented over `java.net.http` in
  * [[OpencodeServer]], stubbed in tests.
  */
private[opencode] trait OpencodeHttp:
  /** POST a JSON `body` to `path` (relative to the server base, e.g.
    * `/session/ses_x/prompt_async`); returns the response body. Throws an
    * [[orca.OrcaFlowException]] on a non-2xx response.
    */
  def postJson(path: String, body: String): String

  /** Open `GET /event` as a source of raw SSE lines. This is the whole server's
    * firehose; the conversation filters it to its own session id. (OpenCode's
    * `?directory=` scoping is the only narrowing the endpoint offers and isn't
    * session-precise, so it's not used.)
    */
  def events(): StreamSource

  /** GET `path` (relative to the server base) and return the HTTP status code.
    * Returns 0 when the server is unreachable or the request fails for any
    * reason — callers treat any non-200 as "session not found".
    */
  def getStatus(path: String): Int = 0

  /** Release transport resources (the HTTP client). Default no-op for stubs;
    * the real client shuts down its connection pool and selector thread. Called
    * once at scope teardown.
    */
  def close(): Unit = ()
