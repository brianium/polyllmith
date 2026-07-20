@AGENTS.md

# Claude-specific notes

Everything substantive lives in `AGENTS.md` (imported above). The notes below apply only when the agent is Claude Code:

- **Formatting** — `cljfmt` + paren repair run automatically via the `clj-paren-repair-claude-hook` hooks configured in `.claude/settings.json`. Don't hand-format; just write the code.
- **Second opinions** — the `/discuss` skill runs the current line of thinking past an external adversary model. Use it to pressure-test a design before it becomes a diff.
