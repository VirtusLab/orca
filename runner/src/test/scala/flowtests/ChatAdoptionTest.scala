package flowtests

/** A flow script continues a durable session's conversation through
  * `session.chat` only: adopting a raw session id stays library-internal.
  */
class ChatAdoptionTest extends munit.FunSuite:

  test("a flow script cannot adopt a session id as a chat"):
    val errors = compileErrors(
      """
      def adopt(a: orca.ClaudeAgent) =
        a.chat(orca.agents.SessionId.fresh[orca.BackendTag.ClaudeCode.type])
      """
    )
    assert(errors.contains("too many arguments for method chat"), errors)
