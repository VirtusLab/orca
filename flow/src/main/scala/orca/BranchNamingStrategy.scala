package orca

import orca.llm.LlmTool
import orca.tools.IssueHandle

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Strategy that produces a git-ref-safe feature-branch name. Resolved once per
  * flow run, inside a stage (the `InStage` token gates the call).
  *
  * `shortenPrompt` (condense userPrompt via llm.cheap, then slug) is deferred
  * to Task E2 — do not add it here.
  */
trait BranchNamingStrategy:
  /** Resolve the feature-branch name. `userPrompt` is the flow's prompt; `llm`
    * is the leading model (used only by the prompt-shortening strategy).
    */
  def resolve(userPrompt: String, llm: LlmTool[?])(using InStage): String

object BranchNamingStrategy:

  /** Git-ref-safe slug (PURE). Lower-case; keep only `[a-z0-9-]`; replace runs
    * of other chars with a single `-`; strip leading/trailing `-`; cap to
    * `maxLen` without leaving a trailing `-`. If the result is empty or still
    * starts with `-`, return `"flow-<shorthash>"` where `<shorthash>` is a
    * short hex hash of `text` — the ref is NEVER empty and NEVER begins with
    * `-` (ADR 0018 §2.5, R2).
    *
    * `maxLen` is clamped to a minimum of 1 so a zero/negative cap can't
    * silently force the fallback.
    */
  def slug(text: String, maxLen: Int = 50): String =
    val cleaned = clean(text)
    val capped = cap(cleaned, math.max(1, maxLen))
    if capped.isEmpty || capped.startsWith("-") then fallback(text)
    else capped

  /** `<prefix>/issue-<number>` — number is an Int, prefix slugged; safe by
    * construction. `resolve` ignores `userPrompt` and `llm`.
    */
  def issue(
      handle: IssueHandle,
      prefix: String = "fix"
  ): BranchNamingStrategy =
    // Slug the prefix once at construction, not on every `resolve` call.
    val prefixSlug = slug(prefix)
    new BranchNamingStrategy:
      def resolve(userPrompt: String, llm: LlmTool[?])(using InStage): String =
        s"$prefixSlug/issue-${handle.number}"

  /** Deterministic strategy: slugs `text` to produce the branch name. `resolve`
    * ignores `userPrompt` and `llm`; `text` is evaluated once per `resolve`
    * call (by-name for callers that want late binding).
    */
  def fromText(text: => String): BranchNamingStrategy =
    new BranchNamingStrategy:
      def resolve(userPrompt: String, llm: LlmTool[?])(using InStage): String =
        slug(text)

  // --------------------------------------------------------------------------
  // Private helpers
  // --------------------------------------------------------------------------

  /** Lower-case, replace every run of non-`[a-z0-9]` chars (after downcasing)
    * with a single `-`, and strip leading/trailing `-`. The `+` already
    * collapses runs, so no second collapsing pass is needed.
    */
  private def clean(text: String): String =
    text.toLowerCase
      .replaceAll("[^a-z0-9]+", "-")
      .stripPrefix("-")
      .stripSuffix("-")

  /** Truncate to at most `maxLen` chars, then strip any trailing `-` that the
    * cut may have exposed.
    */
  private def cap(s: String, maxLen: Int): String =
    s.take(maxLen).stripSuffix("-")

  /** Deterministic `"flow-<8-hex-chars>"` fallback. Uses the first 8 chars of
    * the SHA-256 hex digest of `text` (or of the empty string when `text` is
    * blank) so the name is stable across calls with the same input.
    */
  private def fallback(text: String): String =
    val bytes = MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(StandardCharsets.UTF_8))
    val hex = bytes.map("%02x".format(_)).mkString.take(8)
    s"flow-$hex"
