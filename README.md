# polyllmith

A [deps-new](https://github.com/seancorfield/deps-new) template for scaffolding [Polylith](https://polylith.gitbook.io/polylith) monorepos tuned for **agentic Clojure development** — REPL-driven work with an AI pair (e.g. [Claude Code](https://claude.com/claude-code)), Integrant-composed bases, and a Playwright browser component for verifying what you build.

## Features

- **Polylith architecture by convention** — components/bases/projects wired through root `deps.edn` aliases; no `poly` tool required
- **`AGENTS.md` conventions** — the full playbook (Integrant patterns, REPL discipline, Polylith dependency model, three-tier test discipline) readable by any coding agent; `CLAUDE.md` is a thin Claude-specific shim that imports it
- **Two starter components**:
  - `secrets` — `.env` + system-env credential loading, zero dependencies
  - `browser` — Playwright-Java Chromium automation (profile-keyed sessions, CDP attach to your real browser, screenshots/a11y snapshots) for verifying running web apps from the REPL
- **Harness-agnostic skills** — shared, agent-authored skills live in `.agents/skills/` (readable by any coding agent from `AGENTS.md`); Claude Code discovers them through symlinks under `.claude/skills/`. The bundled `clojure-eval` nREPL skill is set up this way.
- **Claude Code integration** via [clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light) — paren/indent auto-repair hooks (wired natively for both Claude via `.claude/settings.json` and Codex via `.codex/hooks.json`), the shared `clojure-eval` nREPL skill, and a Claude-only `discuss` skill for external second opinions
- **Hot reloading** via [clj-reload](https://github.com/tonsky/clj-reload) with Integrant suspend/resume
- **Quality control** — `bb kondo:lint` plus a three-tier test task family (`bb test`, `test:integration`, `test:smoke`, `test:all`, `test:brick`, `test:affected`) and a ready-made GitHub Actions CI workflow

## Templates

| Template | Description |
|----------|-------------|
| `brianium/polyllmith` | Polylith monorepo for agentically-fueled Clojure apps (JVM) |

## Prerequisites

### Required

- [Clojure CLI](https://clojure.org/guides/install_clojure) 1.11+
- [deps-new](https://github.com/seancorfield/deps-new) installed as a tool:
  ```bash
  clojure -Ttools install-latest :lib io.github.seancorfield/deps-new :as new
  ```

### For working in generated projects

- [Babashka](https://github.com/babashka/babashka) — task runner (`bb test`, `bb kondo:lint`)
- [clj-kondo](https://github.com/clj-kondo/clj-kondo) — linting
- [bbin](https://github.com/babashka/bbin) — to install the clojure-mcp-light CLI tools
- [Docker](https://www.docker.com/) — optional, for local dev services (Postgres)

Each generated project's README carries the full [clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light) install recipe (pinned, with the bbin gotchas documented).

## Usage

### Create a new workspace

```bash
clojure -Sdeps '{:deps {io.github.brianium/polyllmith {:git/tag "v0.1.1" :git/sha "a3da04d"}}}' \
  -Tnew create :template brianium/polyllmith :name myorg/myapp
```

The `:name` must be qualified (`myorg/myapp`). It becomes the workspace's top namespace: bricks live at `myorg.myapp.<brick>.interface` with source under `components/<brick>/src/myorg/myapp/<brick>/`.

For local testing of the template itself:

```bash
clojure -Sdeps '{:deps {io.github.brianium/polyllmith {:local/root "/path/to/polyllmith"}}}' \
  -Tnew create :template brianium/polyllmith :name myorg/myapp
```

### Generated workspace structure

```
myapp/
├── AGENTS.md                     # agent conventions (the big playbook)
├── CLAUDE.md                     # thin Claude-specific shim (@AGENTS.md)
├── README.md                     # workspace README with brick inventory tables
├── deps.edn                      # :dev / :test aliases wiring the bricks
├── bb.edn                        # kondo:lint + three-tier test tasks
├── docker-compose.yml            # postgres:18 for local dev services
├── .env.example                  # secrets skeleton (.env is gitignored)
├── .github/workflows/ci.yml      # PR CI: lint + Tier 1 tests
├── .agents/skills/               # harness-agnostic shared skills (clojure-eval)
├── .claude/                      # Claude hooks (settings.json), discuss skill, clojure-eval symlink → .agents/
├── .codex/                       # Codex hooks (hooks.json) — same paren-repair + cljfmt hook
├── components/
│   ├── secrets/                  # myorg.myapp.secrets.interface
│   └── browser/                  # myorg.myapp.browser.interface
├── bases/                        # (empty — your Integrant systems go here)
├── projects/                     # (empty — deployment payloads go here)
└── development/
    └── src/{user.clj, dev.clj, dev/browser.clj}
```

## Development Workflow (in a generated workspace)

```bash
# Terminal 1 — dev services (optional)
docker compose up -d

# Terminal 2 — nREPL
clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.0"}}}' -M:dev -m nrepl.cmdline --port 7888

# Terminal 3 — your coding agent
claude
```

In the REPL:

```clojure
(dev)               ; switch to the dev namespace
(start config)      ; start an Integrant system
(reload)            ; reload changed namespaces (suspend/resume hot-swap)
(browser-launch!)   ; headed Chromium session that survives (reload)
```

Run tests:

```bash
bb test             # Tier 1 — fast / hermetic
bb test:affected    # Tier 1 for bricks changed since main
bb kondo:lint
```

## License

Copyright © 2026 Brian Scaturro

Distributed under the Eclipse Public License version 1.0.
