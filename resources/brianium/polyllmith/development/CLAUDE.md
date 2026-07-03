# development/

The Polylith development project — home of the `dev` namespace and REPL helpers.

## Reload vs Restart

The `dev` namespace provides three lifecycle functions:

| Function | What it does | When to use |
|----------|-------------|-------------|
| `(reload)` | Recompiles changed namespaces via clj-reload, then `ig/suspend!` + `ig/resume` to hot-swap Integrant handlers | After editing source files — the normal workflow |
| `(restart config)` | `ig/halt!` + reload + `ig/init` from scratch | When `(reload)` isn't picking up changes, or after editing Integrant config maps |
| `(stop)` / `(start config)` | Full teardown / cold start | Switching between system configs |

### The `(require ... :reload)` trap

**Never use `(require 'some.ns :reload)` when a system is running.** It poisons the clj-reload pipeline:

1. `(require 'foo :reload)` loads the file into the JVM
2. `(reload)` checks file mtimes — sees no change since the file was already loaded
3. clj-reload skips recompilation → `ig/resume` re-uses existing Integrant components with old handlers
4. Your edit appears to have no effect

**Fix:** If you accidentally did this, use `(restart config)` which bypasses clj-reload's mtime check by halting and re-initing everything.

## Dev Namespace Helpers

| Helper | Purpose |
|--------|---------|
| `(reload)` | Reload + hot-swap (see above) |
| `(start config)` / `(stop)` / `(restart config)` | Integrant system lifecycle |
| `(browser-launch!)` | Launch a headed REPL browser session (survives `(reload)` — see `dev.browser`) |
| `(browser)` | The current REPL browser session, or nil if closed |
| `(browser-stop!)` | Close the REPL browser session |
