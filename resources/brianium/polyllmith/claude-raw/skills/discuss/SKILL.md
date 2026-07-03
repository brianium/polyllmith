---
name: discuss
description: Run the current line of thinking by the configured external adversary (codex/gpt-5.5) for a genuine second opinion mid-conversation. Use when the user types `/discuss` — with or without steering text — to pressure-test an idea, approach, or design decision being actively iterated on, BEFORE it becomes a spec or a diff. Not a gate; a consult whose result Claude metabolizes back into the live conversation.
metadata:
  author: dot-claude
  version: "1.0"
---

# Discuss Skill

`/discuss` runs the live thread of thinking past a **different mind** — a configured external adversary (codex/gpt-5.5 at `xhigh`) reached as a plain subprocess — and brings back a genuine second opinion. It is a mid-conversation, human-in-the-loop consult:

- **No artifact.** There is no spec triad and no PR diff. The thing under discussion is the live conversation — a half-formed idea, an approach being weighed, a design decision. *You* synthesize the context the adversary needs; there is nothing on disk to point it at.
- **No gate, no verdict.** The output is not a `Verdict:` line you act on. It is a perspective you **metabolize** and bring back into the conversation with the user. You are still the one talking to them.
- **The conversation is the loop.** One consult per invocation. The user reads your synthesis, you keep talking, they invoke `/discuss` again if they want another pass. There is no automated round loop here — the user drives cadence.

The value is **cross-model priors**: the consult is worth running precisely because it is *not* you. Do not substitute a Claude subagent for the configured external adversary — that gives a second read, not a second opinion.

## When to invoke

Invoke when the user types `/discuss`. Two forms:

- `/discuss` — Infer the live question from the conversation. React to the highest-leverage decision currently on the table.
- `/discuss <steering text>` — The user aims the consult (`/discuss press on whether this scales`, `/discuss devil's advocate on the data model`). The steering text becomes `{{question}}`.

This is for thinking that is being **actively iterated in conversation**, not finished work — a written spec or an open PR deserves a proper review pass, not a conversational consult. If the user is clearly asking to review a finished artifact, do that instead.

## Cadence — always deep

Every `/discuss` is a full `xhigh` codex run (~5–10 min), launched **detached** and resumed on its completion notification, because the configured `timeout_ms` (10 min) sits on the agent's ~10-min foreground Bash ceiling, so a foreground launch would be killed mid-run. This is a deliberate *fire-and-resume* consult, not a snappy back-and-forth. When you launch it, tell the user it's running so they can keep working or wait; re-engage when it lands. If a question is too small to be worth a deep round, just answer it directly instead of invoking `/discuss`.

## Config

Read `.claude/skills/discuss/discuss.json`. The `adversary` block is reviewer-agnostic — swapping the model or CLI is a config change, not a SKILL edit:

| Key | Default | Meaning |
|-----|---------|---------|
| `adversary.command` | `"codex exec -m gpt-5.5 -c model_reasoning_effort=xhigh --color never"` | Base CLI. Step 3 appends `-o "$result_file" -` and passes the substituted prompt on stdin. **Plain `codex exec`.** |
| `adversary.prompt_file` | `".claude/skills/discuss/discuss.prompt.md"` | The thought-partner template. Two placeholders: `{{brief}}` and `{{question}}`. |
| `adversary.timeout_ms` | `600000` | 10 min. Bounds the inner `timeout` wrapper. |

## Orchestration

### Step 1 — Build the brief

This is the load-bearing step. The adversary has **zero conversation history**. Everything it knows comes from the brief you write now. Synthesize a self-contained briefing covering:

- **The problem** — what we're actually trying to solve, in a few sentences.
- **The approach being leaned toward** — what the user (and you) are currently inclined to do.
- **The alternative(s) in view** — the road(s) not taken. If the conversation has only surfaced one approach, name the most plausible alternative yourself so the adversary has something to steelman.
- **Hard constraints** — the load-bearing context the adversary can't infer: relevant Polylith bricks, architectural rules (Integrant-in-bases-only, interface-only consumption, mobile-first CSS), prior decisions that are fixed, anything from CLAUDE.md or memory that bounds the design.
- **Tight, named file excerpts** — only when a claim is load-bearing. Reference paths the adversary can read itself rather than pasting whole files.

Two disciplines that protect the consult's value:

1. **Do not lead the witness.** Write the brief *neutrally*. If you frame it to favor the approach you already prefer, the "second opinion" degrades into an echo. Present the leaning approach and the alternative on even footing — steelman the alternative in the brief itself.
2. **Scope it tightly; name what's out of scope.** An unbounded brief invites drift — the adversary will latch onto whatever looks interesting. State the one question on the table and explicitly bound what is *not* being asked.

Set `{{question}}` from the user's steering text, or `"no specific steer — react to the highest-leverage point in the brief"` when they invoked `/discuss` bare.

### Step 2 — Echo what you're asking (one line)

Before launching, surface a single line to the user naming what you're putting to the adversary — e.g. *"Consulting the adversary on: whether to model X as a capability vs. a plain component, given the scheduler precedent."* This is cheap insurance: it lets the user catch a mis-aimed brief before a multi-minute round burns. Do **not** paste the full brief for approval — that adds a round-trip to an already-slow consult; one line is enough. If the user corrects the aim, revise the brief and re-echo; otherwise proceed.

### Step 3 — Launch the consult (detached)

These launch mechanics are the verified-safe shape for a backgrounded reviewer subprocess on this harness.

1. **Allocate temp files with bare `XXXXXX`.** macOS BSD `mktemp` only substitutes `XXXXXX` at the very end of the template — a trailing suffix like `.md` is returned literally and concurrent runs clobber each other. Use no trailing suffix:
   ```bash
   prompt_tmp=$(mktemp /tmp/discuss-prompt.XXXXXX)
   invoke_tmp=$(mktemp /tmp/discuss-invoke.XXXXXX)
   result_file=$(mktemp /tmp/discuss-result.XXXXXX)
   stdout_log=$(mktemp /tmp/discuss-stdout.XXXXXX)
   stderr_log=$(mktemp /tmp/discuss-stderr.XXXXXX)
   trap 'rm -f "$prompt_tmp" "$invoke_tmp"' EXIT
   # result_file/stdout_log/stderr_log are DELIBERATELY NOT trapped: the script
   # runs detached, so its EXIT trap fires when the background shell finishes —
   # BEFORE this turn is resumed to read them. Trap only throwaway inputs.
   ```
   Prefer your session scratchpad over `/tmp` for the durable outputs if you have one; the key invariant is that the outputs survive the backgrounded script's exit.

2. **Substitute placeholders.** Read `adversary.prompt_file`, replace `{{brief}}` with the Step 1 brief and `{{question}}` with the steering text, write the result to `$prompt_tmp`.

3. **Extract `timeout_ms` to an integer** (e.g. `600000`) before the arithmetic below, or `$((timeout_ms / 1000))` evaluates to `0` and the wrapper kills codex instantly.

4. **Write the invocation to `$invoke_tmp`, then exec via `bash`.** The Bash tool runs under zsh, which does not word-split unquoted variables, so a bare `$command` invocation fails on the multi-word CLI string. Bake the value into a literal script via heredoc (outer-shell interpolation), then run it under `bash`:
   ```bash
   if command -v timeout >/dev/null 2>&1; then TIMEOUT_BIN="timeout";
   elif command -v gtimeout >/dev/null 2>&1; then TIMEOUT_BIN="gtimeout";
   else TIMEOUT_BIN=""; fi

   cat > "$invoke_tmp" <<EOF
   #!/bin/bash
   $adversary_command -o "$result_file" - < "$prompt_tmp" \\
     > "$stdout_log" 2> "$stderr_log"
   echo "ADV_EXIT=\$?" >> "$stdout_log"
   EOF

   if [ -n "$TIMEOUT_BIN" ]; then
     "$TIMEOUT_BIN" $((adversary_timeout_ms / 1000)) bash "$invoke_tmp"
   else
     bash "$invoke_tmp"
   fi
   ```
   Launch this whole block with `run_in_background: true`. Tell the user the consult is running (per "Cadence" above) and yield the turn.

### Step 4 — Resume and metabolize

When the completion notification fires:

1. **Check the exit.** Read the `ADV_EXIT=<n>` line appended to `$stdout_log` (the resumed turn has no live `$?`). On non-zero exit or empty `$result_file`, do not fabricate a consult — tell the user it failed, attach the `$stderr_log` tail (~last 40 lines), and offer to re-run. A `124` exit is the timeout wrapper firing.

2. **Read `$result_file`** — the adversary's response.

3. **Metabolize; do not dump.** Bring it back into the conversation as *your* next turn:
   - **Lead with what it changed** (or didn't change) in your thinking. Did it shift your recommendation? Confirm it? Surface something you both missed?
   - **Flag genuine disagreement.** Where the adversary is wrong or overweights something, say so and say why — you are not a relay, you are a participant who just consulted a sharp colleague. A consult you disagree with is still useful signal; report that honestly rather than deferring to it.
   - **Quote its 2–3 sharpest lines verbatim** so the user sees its actual words, including its closing `The one thing I'd push back on hardest:` line. No wall of raw output.

   The user asked you to *consult* the adversary, not to forward its mail. The deliverable is you, returning to the conversation smarter.

### Step 5 — Persistence

These are throwaway consults. Keep the brief and the response in your session scratchpad only; do not write them into the repo. The exception: if the discussion is clearly feeding a design doc that's about to be written, you may save the consult alongside it — but only when the user is heading that way, not by default.
