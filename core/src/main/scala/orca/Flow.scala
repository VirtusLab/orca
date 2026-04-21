package orca

def stage[T](name: String)(body: => T)(using FlowContext): T = ???

def fail(message: String)(using FlowContext): Nothing = ???

def fixLoop(
    evaluate: () => ReviewResult,
    fix: List[ReviewIssue] => IgnoredIssues,
    maxIterations: Int = 10
)(using FlowContext): IgnoredIssues = ???

def reviewAndFix[B <: Backend](
    coder: LlmTool[B],
    sessionId: SessionId[B],
    reviewers: List[LlmTool[?]],
    task: String,
    lintCommand: Option[String] = None,
    confidenceThreshold: Double = 0.7
)(using FlowContext): IgnoredIssues = ???

def lint(
    command: String,
    llm: LlmTool[?]
)(using FlowContext): ReviewResult = ???
