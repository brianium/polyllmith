---
name: clojure-eval
description: Evaluate Clojure code via nREPL using clj-nrepl-eval. Use this when you need to test code, check whether edited files compile, verify function behavior, run tests, or interact with a running REPL session in this Polylith monorepo.
---

# Clojure REPL Evaluation Skill

Evaluate Clojure code against a running nREPL with the `clj-nrepl-eval` command
(from [clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light), pinned
to v0.2.2 — see the root `README.md` for install/pin details).

## When to use this skill

- Verify edited Clojure files compile and load correctly (`(reload)`)
- Test function behavior interactively
- Run tests for a namespace
- Debug expressions in a **persistent** session (state survives between calls)
- Validate code changes before committing

## Workflow

### 1. Discover the nREPL server

```bash
clj-nrepl-eval --discover-ports
```

Lists running nREPL servers grouped by directory (clj, bb, shadow-cljs), and
flags which match the current working directory. Use `--connected-ports` to see
ports you've already evaluated against this session.

### 2. Evaluate code — prefer heredoc

**Heredoc via stdin is the default** — the single-quoted delimiter (`<<'EOF'`)
passes every character through literally, so you never fight shell escaping over
quotes, regexes, backslashes, or `!` (which zsh mangles via history expansion):

```bash
clj-nrepl-eval -p <PORT> <<'EOF'
(next.jdbc/execute-one! ds ["SELECT 1"])
EOF
```

A quoted argument is fine for a short, escaping-free expression:

```bash
clj-nrepl-eval -p <PORT> "(+ 1 2 3)"
```

> Delimiters are auto-repaired before evaluation, so a stray missing paren won't
> block the eval — but don't rely on it for multi-form code.

### 3. Work in the `dev` namespace

Per the project `AGENTS.md`, all REPL work happens in `dev`. Require with
`:reload` to pick up file changes:

```bash
clj-nrepl-eval -p <PORT> <<'EOF'
(dev)
(reload)
(require '[{{top/ns}}.{{main/ns}}.example :as example] :reload)
(example/some-fn arg)
EOF
```

For Integrant systems use `(restart config)` — not `(require ... :reload)`, which
poisons clj-reload for handlers.

## Options

| Option | Description |
|--------|-------------|
| `-p, --port PORT` | nREPL port (required for eval) |
| `-H, --host HOST` | nREPL host (default: 127.0.0.1) |
| `-t, --timeout MS` | Timeout in milliseconds (default: 120000) |
| `-r, --reset-session` | Reset the nREPL session (clears `*e`/`*1`, **not** def'd vars or loaded namespaces) |
| `-c, --connected-ports` | List ports connected this session |
| `-d, --discover-ports` | Find running nREPL servers in the current directory |

## Notes

- **Sessions persist** across invocations per host:port until the server restarts.
- **Always `:reload`** when requiring namespaces so recent edits are picked up.
- **Increase `--timeout`** for long-running evaluations.
- **Don't pass both a quoted arg and a heredoc** — command-line arguments take precedence over stdin, so a heredoc is silently ignored if an arg is also present. Use one or the other.
- **Never pipe** `clj-nrepl-eval` into `grep`/`tail` — capture with `$(...)` or `> file` (a pipe can mask the exit code).
