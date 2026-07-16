# {{name}}

## Project Overview

This is a Clojure project using deps.edn for dependency management.
This project uses the [Polylith](https://polylith.gitbook.io/polylith) software architecture to organize shared code.

When adding a new base or component, update the tables in `README.md` to keep the inventory current.

## Technology Stack

- **Clojure** with deps.edn
- **Polylith** architecture
- **Integrant** for system lifecycle in bases
- **Babashka** as the task runner

---

## Secrets & Credentials

All API keys and credentials live in the root `.env` file. Use the `secrets` component to access them:

```clojure
(require '[{{top/ns}}.{{main/ns}}.secrets.interface :as secrets])

(def s (secrets/load-secrets))
(secrets/get-secret s "OPENAI_API_KEY")
```

- `.env` is gitignored; `.env.example` documents required variables
- Real env vars take precedence over `.env` file values
- When writing code that needs credentials (Integrant configs, REPL scripts, tests), always use the secrets component — never hardcode keys

---

## Infrastructure

Development services run via Docker Compose at the monorepo root.

### Start Development Services

```bash
docker compose up -d
```

### Stop Services

```bash
docker compose down
```

### Reset Database (Destroys Data)

```bash
docker compose down -v
docker compose up -d
```

### Available Services

| Service | Port | Description |
|---------|------|-------------|
| postgres | 5432 | PostgreSQL 18 (user: postgres, pass: postgres, db: app_dev) |

### Connecting to PostgreSQL

If `psql` is not installed locally, use the Docker container:

```bash
docker compose exec postgres psql -U postgres -d <database_name>
```

Each base may use its own database. Check the base's config or `.env` for the correct database name.

---

## Hiccup Authoring

General rules for authoring hiccup markup, regardless of which rendering library a base uses.

### CRITICAL: Single Attribute Map Rule

Hiccup elements accept only ONE attribute map, and it must be the second element of the vector. If you pass multiple maps, the extras render as text:

```clojure
;; WRONG - two attribute maps, second renders as text "{:class ...}"
[:div.my-card {:class "extra"} attrs child]

;; CORRECT - merge computed classes into the single attrs map
[:div.my-card (update attrs :class #(into ["extra"] (if (coll? %) % (when % [%]))))
 child]
```

**Symptom of violation:** Raw maps like `{:card/variant :secondary}` appearing as text in rendered output.

### Children

- Children follow the (optional) attribute map: `[:ul attrs li-1 li-2 ...]`.
- Sequences of children are usually spliced by renderers — prefer `(for ...)` / `(map ...)` returning a seq over manually concatenating vectors.
- Don't assume a React-style `[:<>]` fragment exists — fragment support is renderer-specific. A plain `(list ...)` of elements is the portable way to return siblings.

### Verify Rendered Output

When building UI, verify the rendered result in a real browser rather than trusting the hiccup shape — attribute-map mistakes and renderer quirks are invisible in the data and obvious on screen. See "Browser verification" under Development Setup.

---

## Integrant Patterns

Bases use Integrant for system lifecycle management. **Integrant is not allowed in Polylith components** — see "Polylith Dependency Model" below. Follow these conventions in bases:

### Initializer Functions (Preferred)

For component initialization, define a function with the same name as the key's name part. Integrant 0.9+ automatically finds it:

```clojure
;; In {{top/ns}}.{{main/ns}}.my-base.app namespace
(defn store
  "Create a connection store. Integrant calls this via ::store key."
  [{:keys [type ttl-seconds]}]
  (create-store {:type type :duration-ms (* ttl-seconds 1000)}))

;; In config.clj - no defmethod needed
{::app/store {:type :caffeine :ttl-seconds 30}}
```

Integrant resolves `::app/store` → looks for `store` function in `{{top/ns}}.{{main/ns}}.my-base.app` namespace.

### Prefer Domain-Specific Namespaces for Initializers

Initializer functions should live in the namespace that owns the domain, not in `config.clj`. If a component manages database connections, the initializer belongs in `db.clj`. If it manages auth, it belongs in `auth.clj`. Config.clj should only contain the config map and halt/suspend methods — not thin wrappers around functions that belong elsewhere.

Use the domain namespace's alias in the config key so Integrant finds the function directly:

```clojure
;; In db.clj — the initializer lives where the domain logic lives
(defn datomic
  "Integrant calls this via ::db/datomic key."
  [{:keys [storage-dir system db-name]}]
  (create-client-and-connect ...))

;; In config.clj — require db, use its alias in the key
(ns {{top/ns}}.{{main/ns}}.my-base.config
  (:require [{{top/ns}}.{{main/ns}}.my-base.db :as db]))

{::db/datomic {:storage-dir ".datomic" :system "myapp" :db-name "dev"}}
;; Integrant resolves ::db/datomic → finds `datomic` in {{top/ns}}.{{main/ns}}.my-base.db

;; WRONG — don't create a wrapper in config.clj for something db.clj should own
(defn datomic [{:keys [storage-dir]}]
  (db/create-conn {:storage-dir storage-dir}))  ; Unnecessary indirection
```

The function name must match the key's name part (`datomic`), and the key's namespace alias must resolve to the namespace where the function is defined.

### When defmethod Is Still Required

Use explicit `defmethod` for:
- `ig/halt-key!` - cleanup/shutdown logic
- `ig/suspend-key!` / `ig/resume-key` - hot-reload support
- Complex initialization that can't be expressed as a simple function

```clojure
;; halt-key! always needs defmethod
(defmethod ig/halt-key! ::app/server [_ stop-fn]
  (when stop-fn
    (stop-fn)))
```

### Refs Work with Initializer Functions

`ig/ref` values are resolved before the initializer is called:

```clojure
;; Config with refs
{::app/store    {:type :caffeine}
 ::app/dispatch {:store (ig/ref ::app/store)}}  ; ref to store

;; Initializer receives resolved value
(defn dispatch
  [{:keys [store]}]  ; store is the actual store, not the ref
  (create-dispatch store))
```

### Config Organization

In a Polylith base:
- **app.clj** - Initializer functions, handlers, views
- **config.clj** - Config map, halt/suspend/resume defmethods only
- **core.clj** - Optional system lifecycle for standalone execution
- **main.clj** - Entry point for uberjar (calls core/start)

### Passing Dependencies to Handlers

**Don't use global vars** to access system dependencies in handlers. Instead, pass them through your router's route data (or an equivalent request-scoped mechanism):

```clojure
;; Config: pass store to router
{::app/router {:routes app/routes
               :store  (ig/ref ::app/store)
               :middleware [...]}}

;; Initializer: add to route data
(defn router [{:keys [routes store middleware]}]
  (make-router routes {:data {:store store :middleware middleware}}))

;; Handler: get from request
(defn my-handler [request]
  (let [store (get-in request [:route-data :store])]
    ...))
```

This keeps the dev namespace base-agnostic and avoids coupling handlers to global state.

---

## Babashka Tasks

`bb.edn` is the standard for executable dev processes. Prefer `bb <task>` over shell scripts.

```bash
bb tasks            # List available tasks
bb kondo:lint       # Lint with clj-kondo
```

---

## Development Setup

### CRITICAL: Always Start a REPL for REPL-Driven Tasks

When a task involves REPL-driven development (component iteration, system testing, UI verification), **always start a REPL if one isn't running**. Never skip REPL interaction by "just editing files directly." The REPL is the primary development tool — use `clj-nrepl-eval --discover-ports` to check, and if none are found, start one with the command below.

### CRITICAL: Always Run the REPL from the Current Working Directory

**Never `cd` to the main repo root to start a REPL.** Always start the REPL from whatever directory you're working in (the project root, or a worktree). The REPL's classpath is determined by the directory it starts from. Starting from the wrong directory means:
- File edits won't be picked up by `(reload)`
- Resources won't be on the classpath
- You'll end up syncing files between directories (wrong approach)

When working in a **git worktree**, the worktree IS the project root. Start the REPL there. Symlink `.env` from the main repo if needed (`ln -sf /path/to/main/.env .env`).

### Starting an nREPL Server

```bash
clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.0"}}}' -M:dev -m nrepl.cmdline --port <PORT>
```

This starts a REPL with development dependencies loaded.

### Development Workflow

1. Start REPL with `clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.0"}}}' -M:dev -m nrepl.cmdline --port <PORT>`
2. Switch to dev namespace: `(dev)`
3. Make changes to source files
4. Reload: `(reload)`

The `dev` namespace provides:
- `(reload)` - Reload changed namespaces via clj-reload
- `(start config)` - Start an Integrant system with the given config
- `(stop)` - Stop the running system
- `(restart config)` - Stop, reload, and start

Example with a base:
```clojure
(dev)
(require '[{{top/ns}}.{{main/ns}}.my-base.config :as my-base])
(start my-base/config)
;; => http://localhost:3000

(stop)
(restart my-base/config)
```

See `development/CLAUDE.md` for the reload-vs-restart doctrine (including the `(require ... :reload)` trap).

### Browser Verification

The `browser` component (Playwright-driven Chromium) is the standard way to verify running web apps — don't declare UI work done from the hiccup alone. The dev namespace holds a headed session that survives `(reload)`:

```clojure
(dev)
(browser-launch!)                                   ;; headed window, profile "repl-ui"
(require '[{{top/ns}}.{{main/ns}}.browser.interface :as br])
(br/navigate (browser) "http://localhost:3000")
(br/screenshot! (browser) "/tmp/check.png")          ;; look at it
(browser-stop!)
```

`(browser)` returns the live session (or nil if the window was closed by hand). A reload does NOT re-navigate the open page — after editing server-rendered HTML/CSS, `(reload)` then re-navigate. The component can also attach to your real, already-authenticated browser over CDP — see `components/browser/README.md`.

## Project Structure

```
components/* # Polylith components
development/ # Polylith development project
bases/       # Polylith bases
projects/    # Polylith projects (deployment payloads)
```

## REPL Evaluation

Evaluate code in the running REPL via nREPL using `clj-nrepl-eval`.

### Connecting and Evaluating

```bash
clj-nrepl-eval --discover-ports          # Find running REPLs
clj-nrepl-eval -p <PORT> "(+ 1 2 3)"       # Evaluate expression
```

**Important:** All REPL evaluation should take place in the `dev` namespace:

```bash
clj-nrepl-eval -p <PORT> "(dev)"
clj-nrepl-eval -p <PORT> "(reload)"
```

### Discovering Functions in Unfamiliar Namespaces

Don't guess function names. Use this two-step pattern:

```clojure
;; 1. List public vars
(keys (ns-publics '{{top/ns}}.{{main/ns}}.some.namespace))

;; 2. Check arglists before calling
(:arglists (meta (ns-resolve '{{top/ns}}.{{main/ns}}.some.namespace 'some-fn)))
```

### Shell Escaping: Exclamation Points

The shell strips `!` from command arguments (bash history expansion). Clojure functions like `execute-one!`, `swap!`, `reset!`, etc. will silently lose the `!` and fail with "No such var" errors.

**Fix:** Use a heredoc to pass code to `clj-nrepl-eval`:

```bash
clj-nrepl-eval -p <PORT> "$(cat <<'CLOJURE'
(next.jdbc/execute-one! ds ["SELECT 1"])
CLOJURE
)"
```

The single-quoted `'CLOJURE'` delimiter prevents all shell expansion inside the heredoc.

## Running Tests

In Polylith, each component and base has its own test directory. Always include the `:dev` alias to load component dependencies.

### Inner-loop validation philosophy

**The REPL is the inner-loop validator. Tests are gates at boundaries.**

| When | What |
|------|------|
| Mid-implementation (designing, iterating) | REPL: `(reload)`, invoke the change, inspect the result |
| Before committing | `bb test:affected` (Tier 1, only bricks you've changed) |
| Before opening a PR | `bb test` (Tier 1, full) |
| Pull request | CI runs `bb test` and must pass to merge |

Do not run the full suite (`bb test`, `bb test:integration`, `bb test:all`) as part of the inner loop — the wall time turns iteration into wait. Full runs belong at pre-PR and PR boundaries, not inside the design-and-validate loop.

Tests are still authored as part of feature work — they're part of the deliverable. They're just not run as inner-loop validation. The author validates behavior at the REPL; CI runs the suite.

### Green Tier 1 is non-negotiable

A red Tier-1 (untagged) test blocks merge. Documenting a failure as "known / pre-existing / inherited / out of scope" does **not** make it OK to merge on top of it. If a test is red:

1. **Fix it** (preferred), or
2. **Move it out of the PR gate** if it genuinely needs infra/creds — tag it `^:integration` or `^:smoke` so it leaves Tier 1, and open a tracking issue, or
3. If it must stay red briefly, get explicit sign-off — never let "known failure" become standing license to merge red.

### Test discipline — three tiers

The suite splits into three tiers by deftest metadata:

**Tier 1 — fast / hermetic (untagged).** Pure code in-process: validation throws, factory functions, schema/parse helpers, pure-fn seams. No infra, no creds. Save-loop default.

**Tier 2 — `^:integration`.** Needs *provisioned local services* but no third-party calls: spawns subprocesses, connects to a local database, launches a real browser, or otherwise has wall-time dominated by infrastructure setup.

**Tier 3 — `^:smoke`.** Hits real external APIs. Costs money, has rate limits, can flake. Run manually or on a schedule — **not gated on PRs**.

Tag rule: if the test hits a real external API → `^:smoke`. Else if it needs anything outside a plain JVM (subprocess spawn, database, browser) → `^:integration`. Otherwise leave it untagged so it runs in Tier 1. The two tags are mutually exclusive.

Six `bb` tasks at the project root drive the splits:

```bash
bb test                  # Tier 1 only — excludes :integration + :smoke
bb test:integration      # Tier 2 only — needs local infra
bb test:smoke            # Tier 3 only — needs API keys, costs money
bb test:all              # Tier 1 + 2 (everything except :smoke) — pre-PR sanity for cross-cutting changes
bb test:brick <name>     # all tests for one or more bricks — needs the brick's infra
bb test:affected         # Tier 1 tests for bricks changed since main + working tree
```

`bb test` assumes **no local infra** — no Docker postgres, no browser binaries, no API keys. If CI gates only Tier 1, run `bb test:integration` locally before PR when you've touched the integration surface.

`bb test:affected` derives changed bricks from `git diff <BASE_REF>...HEAD` + working-tree + untracked files (default `BASE_REF=main`). If `deps.edn`, `bb.edn`, or `.github/**` changed it falls back to the full Tier 1 suite. Doc-only changes (`*.md`, `.claude/`) skip silently. Use `DRY_RUN=1 bb test:affected` to see what it would run without executing.

### Background known-long commands (agent contexts)

In an agentic loop, a foreground tool timeout is a **kill switch, not a wait** — a command that runs past the set timeout is killed mid-flight and all progress is lost, forcing a wasteful re-run. Launch known-long commands (full `bb test`, `bb test:integration`, uberjar builds) in the **background from the start**; append `; echo "EXIT=$?" >> <log>` so the completion notification carries a checkable result. Foreground is fine for short commands like `bb test:affected`.

### Run tests for a specific component

Use the `-n` flag to filter by namespace:

```bash
# Single namespace
clj -M:test:dev -n {{top/ns}}.{{main/ns}}.secrets.interface-test

# Multiple namespaces (all tests for one component)
clj -M:test:dev -n {{top/ns}}.{{main/ns}}.browser.driver-test -n {{top/ns}}.{{main/ns}}.browser.profile-test
```

### From the REPL

In the dev namespace, you can run tests interactively:

```clojure
(reload)  ; Reload changed namespaces first
(require '[clojure.test :refer [run-tests]])
(run-tests '{{top/ns}}.{{main/ns}}.secrets.interface-test)
```

### Adding a New Component's Tests

When adding a new component, update the root `deps.edn`:

1. Add the component to `:dev` alias `:extra-deps`
2. Add the test path to `:test` alias `:extra-paths`
3. Add a `-d` entry to `:test` alias `:main-opts`
4. Add any test-only dependencies to `:test` alias `:extra-deps`

## Adding Dependencies

When adding new dependencies in a REPL-connected environment:

1. **Add to the running REPL first** using `clojure.repl.deps/add-lib`:
   ```clojure
   (clojure.repl.deps/add-lib 'metosin/malli {:mvn/version "0.20.0"})
   ```
   Note: The library name must be quoted.

2. **Confirm the dependency works** by requiring and testing it in the REPL.

3. **Only then add to deps.edn** once confirmed working. Keep in mind that Polylith components, bases, and projects all manage their own deps.edn files. The root level deps.edn should only be modified for development dependencies.

This ensures dependencies are immediately available without restarting the REPL.

### Polylith Dependency Model

**Bases and components do NOT declare dependencies on each other.** Inter-project dependencies are wired through the root `deps.edn` `:dev` alias.

**Component/Base deps.edn should only contain:**
- `:paths` — the brick's own source paths
- `:deps` — external third-party dependencies (Maven/Clojars artifacts) that brick directly uses
- `:aliases` — test paths and test-only dependencies

**WRONG — don't do this in a base deps.edn:**
```edn
{:deps {{{top/ns}}.{{main/ns}}/some-component {:local/root "../../components/some-component"}}}
```

**RIGHT — base deps.edn only has external deps:**
```edn
{:paths ["src" "resources"]
 :deps {hiccup/hiccup {:mvn/version "2.0.0"}}  ; External dep the base needs
 :aliases {:test {:extra-paths ["test"]}}}
```

**CRITICAL: Integrant is NOT allowed in Polylith components.**

Components must remain framework-agnostic. Integrant is a system lifecycle tool that belongs exclusively in bases (where systems are composed). Components should:

- Export plain initializer functions (e.g., `store`, `client`, `connect`)
- Export plain cleanup functions (e.g., `halt!`, `close!`, `disconnect!`)
- Never require `integrant.core`
- Never define `ig/init-key`, `ig/halt-key!`, or other Integrant multimethods

Bases wire components into Integrant by:
1. Using the component's initializer function as the Integrant key (auto-resolved via namespace alias)
2. Calling the component's cleanup function from `ig/halt-key!` in the base's config.clj

```clojure
;; In base config.clj
(ns {{top/ns}}.{{main/ns}}.my-base.config
  (:require [{{top/ns}}.{{main/ns}}.some-component.interface :as some-component]))

;; Integrant auto-resolves ::some-component/store to some-component/store function
{::some-component/store {:api-key (secrets/get-secret s "SOME_API_KEY")}}

;; Base owns the halt-key! — calls component's plain halt! function
(defmethod ig/halt-key! ::some-component/store [_ store]
  (some-component/halt! store))
```

**How components become available to bases:**
1. Root `deps.edn` `:dev` alias includes all bricks via `:local/root`
2. When running `clj -M:dev`, all components and bases are on classpath
3. Bases simply `require` component interfaces — no explicit dependency declaration

**Components are consumed ONLY through their interface namespaces.** Never require another brick's `core`/`impl` namespaces from outside that brick.

### Interface / core separation (slim interfaces)

A brick's `interface.clj(c|d|s)` is a **contract, not an implementation.** Keep it as slim as
possible: it requires the brick's implementation namespace(s) and re-exposes the public API —
thin delegating `defn`s (which carry the public docstrings) and `def` aliases for public data.
**All real logic lives in `core.clj(c|d|s)`.** The rule of thumb: if you're writing anything
beyond a one-line delegation in an interface, it belongs in core. The `secrets` component is the
reference example:

```clojure
;; interface.clj — slim: docstrings + delegation only
(ns {{top/ns}}.{{main/ns}}.secrets.interface
  (:require [{{top/ns}}.{{main/ns}}.secrets.core :as core]))

(defn load-secrets
  "Public docstring lives here."          ; the interface is the API's doc surface
  ([]     (core/load-secrets))
  ([path] (core/load-secrets path)))

;; core.clj — the actual implementation
(ns {{top/ns}}.{{main/ns}}.secrets.core ...)
(defn load-secrets ([] ...) ([path] ...))
```

For a component that exposes **data** rather than behavior, alias the vars:
`(def config core/config)` — the data itself lives in `core`.

**One `core`, or several domain namespaces.** The default is a single `core`. When a component's
implementation naturally splits into isolated domains, those **domain namespaces *are* the "core"
files**, and the interface delegates to each — e.g. a component might keep `parse` and `render`
implementation namespaces, with its interface re-exposing the public fns from each. The contract
is unchanged either way — **the interface stays slim and imports the implementation namespace(s),
and nothing outside the brick requires anything but the interface.**

**When creating a new brick**, add it to root `deps.edn` `:dev` alias:
```edn
:dev {:extra-deps {{{top/ns}}.{{main/ns}}/new-component {:local/root "components/new-component"}
                   {{top/ns}}.{{main/ns}}/new-base {:local/root "bases/new-base"}}}
```

**Every new component and base MUST include a `README.md`** at its root (e.g., `components/browser/README.md`). The README should cover:
- One-line description
- Overview of what the component does
- Quick Start with a code example
- API Reference table of public functions
- Testing instructions (`clj -M:test:dev -n ...`)

## Code Style

- Follow standard Clojure conventions
- Use `cljfmt` formatting
- Prefer pure functions where possible

### Mobile-First CSS

All CSS must follow a **mobile-first** paradigm:
- Default styles target mobile (small screens)
- Use `@media (min-width: ...)` queries to layer on desktop/tablet styles
- Never write desktop-default CSS with `max-width` overrides for mobile

### Namespace Aliases Over Fully Qualified Names

Always require namespaces with aliases. Never use fully qualified function calls inline.

```clojure
;; WRONG - fully qualified
(clojure.string/join ", " items)

;; RIGHT - require with alias
(ns {{top/ns}}.{{main/ns}}.my-base.app
  (:require [clojure.string :as str]))

(str/join ", " items)
```

### Namespaced Keywords

Clojure has two syntaxes for namespaced keywords:

**Single colon (`:`)** - Explicit namespace, works anywhere:
```clojure
:my.app.config/timeout    ; Fully qualified namespace
:ui/visible               ; Arbitrary namespace (doesn't need to exist)
:db/id                    ; Common convention for domain markers
```

**Double colon (`::`)** - Auto-resolved namespace:
```clojure
;; In namespace my.app.core:
::key                     ; Expands to :my.app.core/key

;; With required aliases:
(require '[my.app.db :as db])
::db/query                ; Expands to :my.app.db/query
```

**When to use which:**
- Use `:` with explicit namespace when the keyword meaning is independent of the current file
- Use `::` when the keyword is specific to the current namespace
- Use `::alias/key` to reference keywords from required namespaces without typing the full name
- Prefer `:` for spec keys, component IDs, and data that crosses namespace boundaries

### Domain Maps: schemas + qualified keys

This is a hard pattern to stop proliferating once it begins, so enforce it from the first data-centric move — every
plan that accepts or produces a map with a specific shape must follow these rules.

**1. Always a malli schema for a domain-specific map.** A map with a known shape gets a malli schema, and it is
**validated at its boundary** (constructor, the function that accepts it, registration, or a test/dev assert) so a
malformed map fails loud rather than flowing on as a `(:some-key m)`-and-a-prayer value. The schema is also what makes
the qualified-key rules below *safe* — a free qualified keyword has no compile-time existence check, so a typo
silently doesn't match; the schema recovers that safety.

**2. Qualified keywords, never naked, for domain maps.** Prefer in this order:

- **`::key` (auto-resolved to the current namespace)** for keys that are an **API surface of one namespace** and are
  *not* serialized far from it — registry keys, dispatch markers. Keys that are part of a component's public API
  should live in / resolve to the interface namespace.
- **`:responsibility/key` (a plain qualified keyword namespaced by responsibility, not by a code module)** —
  e.g. `:trust/owner?`, `:trust/level`, `:origin/channel`, `:msg/transport` — for a **cross-cutting map that many
  namespaces produce/consume, or that gets serialized/logged.** This is the right choice when the map outlives or is
  decoupled from any one module's name: `::key` literally bakes the *module name* into the keyword, so renaming the
  namespace silently changes the keyword and breaks every serialized value and cross-version comparison. Responsibility
  namespacing (the `:db/id` idiom) survives the rename.
- **naked `:key`** — avoid for any domain map.

**3. Don't reach for another ns's alias when `::key` fits.** `::key` over `::some-imported-ns/key` when the key
belongs to the current namespace.

Rule of thumb: *ns-owned API key → `::key`; cross-cutting / serialized domain map → `:responsibility/key`; both always
backed by a malli schema.*

## Git Commits

Use conventional commits format:

```
<type>: <description>

[optional body]
```

Types: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`

Examples:
- `feat: add user authentication`
- `fix: resolve nil pointer in data parser`
- `refactor: simplify database connection logic`
