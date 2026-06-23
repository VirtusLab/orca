package orca

import orca.llm.{
  Announce,
  AutonomousTextCall,
  BackendTag,
  JsonData,
  LlmCall,
  LlmConfig,
  LlmTool,
  ToolSet
}
import orca.tools.IssueHandle

/** Tests for BranchNamingStrategy.slug (security-critical) and the
  * issue/fromText factory strategies.
  */
class BranchNamingTest extends munit.FunSuite:

  /** Throwing stub — issue/fromText must never touch the LLM. */
  private object ThrowingLlm extends LlmTool[BackendTag.ClaudeCode.type]:
    val name: String = "throwing-stub"
    def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] =
      throw new AssertionError("LLM must not be called")
    def withConfig(c: LlmConfig): LlmTool[BackendTag.ClaudeCode.type] = this
    def withSystemPrompt(p: String): LlmTool[BackendTag.ClaudeCode.type] = this
    def withName(n: String): LlmTool[BackendTag.ClaudeCode.type] = this
    def withTools(t: ToolSet): LlmTool[BackendTag.ClaudeCode.type] = this
    def resultAs[O: JsonData: Announce]
        : LlmCall[BackendTag.ClaudeCode.type, O] =
      throw new AssertionError("LLM must not be called")

  private given InStage = InStage.unsafe

  // ---------------------------------------------------------------------------
  // slug: basic transformation
  // ---------------------------------------------------------------------------

  test("slug lower-cases input"):
    assertEquals(BranchNamingStrategy.slug("Hello World"), "hello-world")

  test("slug keeps alphanumeric and hyphens"):
    assertEquals(BranchNamingStrategy.slug("abc-123"), "abc-123")

  test("slug maps spaces to hyphens"):
    assertEquals(BranchNamingStrategy.slug("add a feature"), "add-a-feature")

  test("slug maps punctuation to hyphens"):
    assertEquals(
      BranchNamingStrategy.slug("Add a Multiply Function!"),
      "add-a-multiply-function"
    )

  test("slug maps unicode/emoji to hyphens"):
    assertEquals(BranchNamingStrategy.slug("café résumé"), "caf-r-sum")

  // ---------------------------------------------------------------------------
  // slug: collapse and strip
  // ---------------------------------------------------------------------------

  test("slug collapses consecutive hyphens"):
    assertEquals(BranchNamingStrategy.slug("a---b"), "a-b")

  test("slug strips leading hyphens"):
    assertEquals(BranchNamingStrategy.slug("-foo"), "foo")

  test("slug strips trailing hyphens"):
    assertEquals(BranchNamingStrategy.slug("foo-"), "foo")

  // ---------------------------------------------------------------------------
  // slug: security — never starts with '-'
  // ---------------------------------------------------------------------------

  test("slug with leading '-rf foo' does not start with '-'"):
    val result = BranchNamingStrategy.slug("-rf foo")
    assert(!result.startsWith("-"), s"started with '-': $result")
    assert(result.nonEmpty, "must not be empty")

  test("slug with all-punctuation input does not start with '-'"):
    val result = BranchNamingStrategy.slug("!!! x")
    assert(!result.startsWith("-"), s"started with '-': $result")
    assert(result.nonEmpty, "must not be empty")

  // ---------------------------------------------------------------------------
  // slug: security — never empty
  // ---------------------------------------------------------------------------

  test("slug with only emoji returns non-empty fallback"):
    val result = BranchNamingStrategy.slug("💥✨")
    assert(result.nonEmpty, "must not be empty")
    assert(result.matches("^[a-z0-9][a-z0-9-]*$"), s"invalid ref: $result")

  test("slug with only punctuation returns non-empty fallback"):
    val result = BranchNamingStrategy.slug("!!!")
    assert(result.nonEmpty, "must not be empty")
    assert(result.matches("^[a-z0-9][a-z0-9-]*$"), s"invalid ref: $result")

  test("slug with empty string returns non-empty fallback"):
    val result = BranchNamingStrategy.slug("")
    assert(result.nonEmpty, "must not be empty")
    assert(result.matches("^[a-z0-9][a-z0-9-]*$"), s"invalid ref: $result")

  // ---------------------------------------------------------------------------
  // slug: result always matches git-ref regex
  // ---------------------------------------------------------------------------

  test("slug result matches ^[a-z0-9][a-z0-9-]*$"):
    val result = BranchNamingStrategy.slug("Fix: Issue #42 — some weird text!")
    assert(
      result.matches("^[a-z0-9][a-z0-9-]*$"),
      s"invalid ref: $result"
    )

  // ---------------------------------------------------------------------------
  // slug: length cap
  // ---------------------------------------------------------------------------

  test("slug caps result to maxLen"):
    val long = "a" * 100
    val result = BranchNamingStrategy.slug(long, maxLen = 20)
    assert(result.length <= 20, s"length ${result.length} > 20")

  test("slug cap does not produce trailing hyphen"):
    // Input that produces hyphens near the cap boundary
    val input = "abc-def-ghi-jkl-mno-pqr"
    val result = BranchNamingStrategy.slug(input, maxLen = 10)
    assert(!result.endsWith("-"), s"trailing hyphen: $result")
    assert(result.length <= 10, s"too long: ${result.length}")

  test("slug default maxLen is 50"):
    val long = "a" * 100
    val result = BranchNamingStrategy.slug(long)
    assert(result.length <= 50, s"length ${result.length} > 50")

  // ---------------------------------------------------------------------------
  // slug: deterministic fallback contains 'flow-'
  // ---------------------------------------------------------------------------

  test("slug fallback for empty input starts with 'flow-'"):
    val result = BranchNamingStrategy.slug("")
    assert(result.startsWith("flow-"), s"expected 'flow-' prefix: $result")

  test("slug fallback for all-punctuation starts with 'flow-'"):
    val result = BranchNamingStrategy.slug("💥✨")
    assert(result.startsWith("flow-"), s"expected 'flow-' prefix: $result")

  // ---------------------------------------------------------------------------
  // issue strategy
  // ---------------------------------------------------------------------------

  test("issue strategy resolves to fix/issue-<number>"):
    val handle = IssueHandle("acme", "repo", 42)
    val strategy = BranchNamingStrategy.issue(handle)
    val result = strategy.resolve("ignored prompt", ThrowingLlm)
    assertEquals(result, "fix/issue-42")

  test("issue strategy with custom prefix slugs the prefix"):
    val handle = IssueHandle("acme", "repo", 7)
    val strategy = BranchNamingStrategy.issue(handle, prefix = "feature")
    val result = strategy.resolve("ignored", ThrowingLlm)
    assertEquals(result, "feature/issue-7")

  test("issue strategy prefix is slugged"):
    val handle = IssueHandle("acme", "repo", 3)
    val strategy = BranchNamingStrategy.issue(handle, prefix = "Hot Fix!")
    val result = strategy.resolve("ignored", ThrowingLlm)
    assertEquals(result, "hot-fix/issue-3")

  // ---------------------------------------------------------------------------
  // fromText strategy
  // ---------------------------------------------------------------------------

  test("fromText strategy slugs the text"):
    val strategy = BranchNamingStrategy.fromText("Add a Multiply Function!")
    val result = strategy.resolve("ignored prompt", ThrowingLlm)
    assertEquals(result, "add-a-multiply-function")

  test("fromText strategy ignores userPrompt and llm"):
    val strategy = BranchNamingStrategy.fromText("my-feature")
    val result = strategy.resolve("should be ignored", ThrowingLlm)
    assertEquals(result, "my-feature")
