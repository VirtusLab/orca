package orca.effect

import language.experimental.captureChecking

import scala.concurrent.duration.FiniteDuration

/** A status and the body, decoded with the Content-Type charset (UTF-8 when
  * absent or unsupported).
  */
final case class HttpReply(status: Int, body: String):
  def isSuccess: Boolean = status / 100 == 2

/** An open response body read as lines, e.g. a server-sent-event stream. */
trait HttpLines:
  /** Ends at the body's end; throws `java.io.IOException` once [[close]]d or
    * once the reading thread is interrupted.
    */
  def lines: Iterator[String]

  /** Unblocks a thread reading [[lines]]. Callable from any thread, more than
    * once.
    */
  def close(): Unit

/** Requests to one server, from [[Network.httpClient]]. `path` is appended to
  * the base URL. Transport failures and timeouts throw `java.io.IOException`.
  */
trait HttpClient:
  def get(path: String, timeout: FiniteDuration): HttpReply
  def postJson(path: String, json: String, timeout: FiniteDuration): HttpReply

  /** Returns once headers arrive, whatever the status; no timeout bounds the
    * body.
    */
  def openLines(path: String): HttpLines
