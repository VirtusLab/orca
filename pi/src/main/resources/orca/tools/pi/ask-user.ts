
export default function(pi) {
  pi.registerTool({
    name: "__TOOL_NAME__",
    label: "Ask User",
    description: "Ask the human user one concise clarifying question and wait for their answer.",
    promptSnippet: "Ask the user a clarifying question when necessary",
    promptGuidelines: [
      "Use __TOOL_NAME__ only when a human answer is required to proceed.",
      "Ask exactly one concise, actionable question.",
      "Do not use __TOOL_NAME__ for information you can infer or inspect yourself."
    ],
    parameters: {
      type: "object",
      properties: {
        question: {
          type: "string",
          description: "The concise question to ask the human user."
        }
      },
      required: ["question"],
      additionalProperties: false
    },
    async execute(_toolCallId, params, _signal, _onUpdate, ctx) {
      const answer = await ctx.ui.input(params.question);
      const text = answer ?? "";
      return {
        content: [{ type: "text", text }],
        details: { answer: text }
      };
    }
  });
}
