package flowtests

/** A flow script reaches a conversation only through the handle that bundles it
  * with its agent: adopting a raw session id, or pairing a plan with a chat
  * that did not produce it, stays library-internal.
  */
class ChatAdoptionTest extends munit.FunSuite:

  test("a flow script cannot mint a session id"):
    val errors = compileErrors(
      "orca.agents.SessionId.fresh[orca.BackendTag.ClaudeCode.type]"
    )
    assert(errors.contains("cannot be accessed"), errors)

  test("a flow script cannot adopt a session id as a chat"):
    val errors = compileErrors(
      """
      def adopt(a: orca.ClaudeAgent) = a.chat("some-session-id")
      """
    )
    assert(errors.contains("too many arguments for method chat"), errors)

  test("a flow script cannot pair a value with a chat"):
    val errors = compileErrors(
      """
      def pair(c: orca.Chat[?], p: orca.plan.Plan) = orca.plan.WithChat(c, p)
      """
    )
    assert(
      errors.contains(
        "WithChat in package orca.plan does not take parameters"
      ),
      errors
    )

  test("a flow script cannot swap a WithChat's chat"):
    val errors = compileErrors(
      """
      def swap(s: orca.plan.WithChat[orca.plan.Plan], c: orca.Chat[?]) =
        s.copy(chat = c)
      """
    )
    assert(errors.contains("cannot be accessed"), errors)
