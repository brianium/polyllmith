# secrets

Environment variable management with `.env` file parsing and system environment variable fallback.

## Overview

Loads secrets from `.env` files with system environment variables taking precedence. Designed for REPL-friendly development where configuration can be reloaded without restarting the JVM. No external dependencies.

## Quick Start

```clojure
(require '[{{top/ns}}.{{main/ns}}.secrets.interface :as secrets])

;; Load from .env in current directory
(def sec (secrets/load-secrets))

;; Or from a custom path
(def sec (secrets/load-secrets "path/to/.env"))

;; Get a secret
(secrets/get-secret sec "OPENAI_API_KEY")
;; => "sk-..."

;; Or just use it as a map
(get sec "DATABASE_URL")
```

## API Reference

| Function | Signature | Description |
|----------|-----------|-------------|
| `load-secrets` | `()` or `(path)` | Load secrets from `.env` file (default `".env"`), merged with system env vars |
| `get-secret` | `(secrets key)` | Get a secret value by key from the loaded map |
| `parse-env-file` | `(path)` | Low-level: parse `.env` file into a map. Returns nil if file doesn't exist |

### Precedence

System environment variables override `.env` file values. This makes the same code work in development (`.env` file) and production (real env vars) without changes.

### .env Format

```
# Comments are ignored
DATABASE_URL=postgres://user:pass@host/db
API_KEY=secret123
COMPLEX_VALUE=has=equals=signs
```

- `KEY=VALUE` per line, splits on first `=`
- Lines starting with `#` are ignored
- Blank lines are ignored

## Testing

```bash
clj -M:test:dev -n {{top/ns}}.{{main/ns}}.secrets.interface-test
```

Tests cover `.env` parsing, comment/blank line handling, values with equals signs, missing files, env var precedence, and key retrieval.
