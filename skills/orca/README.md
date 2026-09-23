# Installing the orca skill

[SKILL.md](SKILL.md) teaches coding agents when and how to delegate
implementation tasks to Orca. Install it into your harness:

- **Claude Code**: as a plugin — `/plugin marketplace add VirtusLab/orca`,
  then `/plugin install orca@orca-skills` (manifests: repo-root
  `.claude-plugin/marketplace.json`, `skills/orca/.claude-plugin/plugin.json`).
  Or copy/symlink this directory to `~/.claude/skills/orca` (personal)
  or `<project>/.claude/skills/orca` (project) — no manifest needed.
- **Pi**: `pi install git:github.com/VirtusLab/orca` (pi auto-discovers the
  `skills/` directory; no manifest needed). Or copy/symlink to
  `~/.pi/agent/skills/orca` (personal) or `<project>/.agents/skills/orca`
  (project).
- **OpenCode**: copy/symlink to `~/.config/opencode/skills/orca`
  (personal) or `<project>/.opencode/skills/orca` (project) — no
  manifest needed. OpenCode also scans `.claude/skills/`, so the Claude Code
  symlink above is picked up too.
- **Codex**: copy/symlink to `~/.agents/skills/orca` (personal) or
  `<project>/.agents/skills/orca` (project) — no manifest needed. Codex
  has no package/marketplace install mechanism; directory placement is the
  whole story.
