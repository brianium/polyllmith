# polyllmith

A clean-slate [Polylith](https://polylith.gitbook.io/polylith) monorepo template for building agentically-fueled Clojure applications — REPL-driven development with an AI pair, Integrant-composed bases, and a browser component for verifying what you build.

Polylith organizes code as small, single-purpose **components** exposed through interface namespaces, composed into runnable **bases**, and packaged as deployable **projects**. This repo uses the Polylith *architecture* by convention (no `poly` tool required): the wiring lives in the root `deps.edn` aliases, and the conventions live in [`AGENTS.md`](AGENTS.md).

## Components

| Component | Description |
|-----------|-------------|
| `secrets` | Environment variable management with `.env` file parsing and system env fallback. Zero dependencies. See [`components/secrets/README.md`](components/secrets/README.md). |
| `browser` | Playwright-Java browser automation — profile-keyed Chromium sessions, CDP attach to your real browser, page perception (HTML, a11y snapshots, screenshots, network log). See [`components/browser/README.md`](components/browser/README.md). |

## Bases

Runnable systems composed from components, with Integrant lifecycle.

| Base | Description |
|------|-------------|
| _(none yet)_ | |

## Projects

Deployment payloads — Dockerfile, docker-compose.yml, scripts, and deployment docs per runtime target.

| Project | Description |
|---------|-------------|
| _(none yet)_ | |

## Development

This template is built for **REPL-driven development with an AI pair** — an agent connected to a running nREPL, reloading namespaces and evaluating code as it goes. The root [`AGENTS.md`](AGENTS.md) gives the agent the project's conventions; the tools below give it hands.

### Prerequisites

- **[Clojure CLI](https://clojure.org/guides/install_clojure)** — `brew install clojure/tools/clojure`
- **[Babashka](https://github.com/babashka/babashka)** — `brew install borkdude/brew/babashka`
- **[bbin](https://github.com/babashka/bbin)** — `brew install babashka/brew/bbin`
- **[clj-kondo](https://github.com/clj-kondo/clj-kondo)** — `brew install borkdude/brew/clj-kondo`
- **[Docker](https://www.docker.com/)** — for Postgres and other dev services (`docker compose up -d`)
- **[Claude Code](https://claude.com/claude-code)** — or any coding agent that reads `AGENTS.md`

### Install `clojure-mcp-light`

[`clojure-mcp-light`](https://github.com/bhauman/clojure-mcp-light) is a trio of CLI tools that let an LLM talk to a running nREPL and automatically repair delimiter errors in Clojure edits before they hit disk. **These are not MCP servers** — they're plain shell commands the assistant invokes directly.

The commands below pin to **v0.2.2** (commit `cf48cc6`). We pin by explicit
`--git/tag` + `--git/sha` so installs are reproducible:

```bash
# 1. nREPL eval from the shell (discovery, persistent sessions, auto-repair)
bbin install https://github.com/bhauman/clojure-mcp-light.git \
  --git/tag v0.2.2 --git/sha cf48cc6ab1d79809a97a74355492d44ee3bbc4ba \
  --as clj-nrepl-eval \
  --main-opts '["-m" "clojure-mcp-light.nrepl-eval"]'

# 2. Claude Code Pre/PostToolUse hook that auto-fixes delimiter errors (zero tokens)
bbin install https://github.com/bhauman/clojure-mcp-light.git \
  --git/tag v0.2.2 --git/sha cf48cc6ab1d79809a97a74355492d44ee3bbc4ba \
  --as clj-paren-repair-claude-hook \
  --main-opts '["-m" "clojure-mcp-light.hook"]'

# 3. On-demand paren repair (covers Bash-based edits the hook doesn't see)
bbin install https://github.com/bhauman/clojure-mcp-light.git \
  --git/tag v0.2.2 --git/sha cf48cc6ab1d79809a97a74355492d44ee3bbc4ba \
  --as clj-paren-repair \
  --main-opts '["-m" "clojure-mcp-light.paren-repair"]'
```

Verify:

```bash
bbin ls                          # all three should read "v0.2.2"
clj-nrepl-eval --help
clj-paren-repair-claude-hook --help
clj-paren-repair --help
```

> **Two bbin gotchas worth knowing** (they cost real time the first time):
> - Use the **fully-qualified** `--git/tag` / `--git/sha` flags. bbin 0.2.5 does
>   not recognize the short `--tag` from upstream's README — it silently falls
>   back to `--latest-sha` (installs `main`, not the tag).
> - Pin `--git/sha` to the **dereferenced commit**, not the tag-object sha.
>   `v0.2.2` is an *annotated* tag, so `git rev-parse v0.2.2` returns the tag
>   object; bbin bakes that string into the script's `script-root` path, but
>   tools.deps checks out the commit — the mismatch makes every invocation fail
>   with a bogus "Cannot run program java" error. Get the right sha with
>   `git rev-parse v0.2.2^{}`.
>
> To bump later, check the [upstream README](https://github.com/bhauman/clojure-mcp-light)
> for the newest tag, resolve its commit with `git rev-parse <tag>^{}`, and swap
> both values above.

### Wire up the Claude Code hook

This repo's `.claude/settings.json` already wires the parinfer/cljfmt hook for sessions started here. To get the same behavior in every project, add it to `~/.claude/settings.json` (merge with any existing `hooks` block). Delimiter errors get fixed transparently — no tokens spent, no "paren death loop":

```json
{
  "hooks": {
    "PreToolUse": [
      { "matcher": "Write|Edit",
        "hooks": [{ "type": "command", "command": "clj-paren-repair-claude-hook --cljfmt" }] }
    ],
    "PostToolUse": [
      { "matcher": "Edit|Write",
        "hooks": [{ "type": "command", "command": "clj-paren-repair-claude-hook --cljfmt" }] }
    ],
    "SessionEnd": [
      { "hooks": [{ "type": "command", "command": "clj-paren-repair-claude-hook --cljfmt" }] }
    ]
  }
}
```

### Start a session

```bash
# Terminal 1 — dev services (optional; only if your bases need them)
docker compose up -d

# Terminal 2 — nREPL
clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.0"}}}' -M:dev \
  -m nrepl.cmdline --port 7888

# Terminal 3 — your coding agent, from the project root
claude
```

From here, just ask the agent to do the work. It will:

- Discover the running REPL with `clj-nrepl-eval --discover-ports`
- Drop into the `dev` namespace (`(dev)`) and `(reload)` after edits
- Evaluate, inspect, and iterate — all without leaving the conversation

> **Always start the REPL from the directory you're working in** (repo root or worktree). The classpath is anchored there — starting from elsewhere breaks `(reload)` and resource lookup. See [`AGENTS.md`](AGENTS.md) for the full REPL workflow and conventions.

### Running tests

The suite splits into three tiers by `^:integration` / `^:smoke` deftest metadata. See [`AGENTS.md`](AGENTS.md#test-discipline--three-tiers) for the full rules; quick reference:

```bash
bb test                  # Tier 1 (hermetic, no infra) — save-loop default
bb test:integration      # Tier 2 (local services, subprocesses) — needs infra
bb test:smoke            # Tier 3 (real external APIs) — needs API keys, costs $
bb test:all              # Tier 1 + 2 — pre-PR sanity check
bb test:brick <name>     # one or more bricks (components/, bases/)
bb test:affected         # Tier 1 tests for bricks changed since main

# Single namespace (any tier, no metadata filter)
clj -M:test:dev -n polyllmith.secrets.interface-test
```

### Linting

```bash
bb kondo:lint            # clj-kondo over bases/ + components/ (auto-imports configs on first run)
```
