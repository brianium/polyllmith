# browser

Playwright-Java browser-automation primitive — profile-keyed Chromium sessions with launch, CDP attach, navigation, interaction, and page perception.

## Overview

A thin, opinionated wrapper around [Playwright Java](https://playwright.dev/java) that supports profile-keyed sessions (so logged-in cookie state survives close/relaunch), explicit headless vs headed launch, CDP attach to an already-running browser, and page perception (HTML, accessibility snapshots, screenshots, network log).

Two architectural rules shape the brick:

1. **Single-threaded session executor.** Playwright Java's docs say its objects are not thread-safe. Every session owns its own `Executors/newSingleThreadExecutor`; all operations submit work to that executor and block on the result. Production code never calls Playwright methods directly from arbitrary threads.

2. **`driver.clj` is the only namespace that imports `com.microsoft.playwright.*`.** `interface.clj` exposes plain functions that return a Clojure map. Raw Java objects (the `Playwright` instance, `BrowserContext`, `Page`, and the executor) live behind internal `::driver/*` keys on that map — callers depend only on the public keys (`:profile`, `:mode`, `:headed?`, `:user-data-dir`, plus `:url` after navigate). This boundary keeps the rest of the system Java-import-free and means downstream bricks can mock the session shape without dragging Playwright onto their classpath.

## Quick Start

```clojure
(require '[polyllmith.browser.interface :as browser])

;; Launch a profile-keyed headless Chromium session.
(def s (browser/launch! {:profile "demo" :headed? false}))
;; => {:profile "demo"
;;     :headed? false
;;     :mode :read-only
;;     :user-data-dir "/.../.browser-profiles/demo"
;;     :url nil
;;     ;; opaque internal keys also present
;;     }

;; Navigate. The returned session has :url updated.
(def s' (browser/navigate s "https://example.com"))
(:url s')
;; => "https://example.com/"

;; Sentinel-selector perception — "is the page logged in?"
(browser/logged-in? s' "h1")
;; => true

;; Accessibility snapshot — a plain readable string with role/name pairs.
(browser/a11y-snapshot s')
;; => {:format :playwright-aria-snapshot
;;     :snapshot "- heading \"Example Domain\" [level=1]\n  - ..."}

;; Idempotent close.
(browser/close! s')
;; => :ok
(browser/close! s') ;; safe to call twice
;; => :ok
```

A second launch with the same `:profile` reuses the on-disk user-data-dir, so cookies and storage that the previous session left behind (cookies with `Max-Age`/`Expires`, IndexedDB, etc.) are still available.

## API Reference

All public functions live on `polyllmith.browser.interface`. Sessions are opaque — depend only on the public keys.

| Function | Signature | Returns |
|----------|-----------|---------|
| `launch!` | `(launch! opts)` | session map: `{:profile <string> :mode <kw> :headed? <bool> :user-data-dir <path> :url nil-until-navigated ...internal...}` |
| `connect!` | `(connect! opts)` | session map of the same shape, attached to a running browser over CDP |
| `navigate` | `(navigate session url)` | session map with `:url` set to Playwright's post-navigation URL |
| `new-page!` | `(new-page! session opts)` | session map rebound to a fresh Page in the same BrowserContext |
| `logged-in?` | `(logged-in? session sentinel-selector)` | `true` if the CSS selector matches an element on the current page, else `false` |
| `a11y-snapshot` | `(a11y-snapshot session)` | `{:format :playwright-aria-snapshot :snapshot <string>}` |
| `click!` | `(click! session selector)` | clicks the element matching `selector` |
| `type-into!` | `(type-into! session selector text)` | fills the matching element via Playwright's idempotent `Locator.fill` |
| `keyboard-type!` | `(keyboard-type! session text)` | types through the page-level keyboard into the focused element |
| `press-key!` | `(press-key! session key)` | presses a Playwright key string (`"Enter"`, `"Control+A"`, ...) |
| `scroll!` | `(scroll! session delta-x delta-y)` | dispatches a mouse-wheel event with pixel deltas |
| `wait-for-condition!` | `(wait-for-condition! session condition)` | waits for `{:selector ...}` / `{:url-pattern ...}` / `{:load-state ...}` / `{:timeout-ms ...}` |
| `grant-permissions!` | `(grant-permissions! session permissions origin)` | grants browser-context permissions, optionally origin-scoped |
| `set-clipboard-text!` | `(set-clipboard-text! session text)` | sets the local system clipboard for subsequent browser paste |
| `page-content` | `(page-content session)` | full HTML string of the live DOM (post-JS, not the original response body) |
| `download-text!` | `(download-text! session url)` | text body of a download made with the session's authenticated context |
| `evaluate!` | `(evaluate! session js)` / `(evaluate! session js args)` | deserialized result of executing `js` in the page |
| `screenshot!` | `(screenshot! session path)` | absolute path string of the written PNG |
| `network-log-snapshot` | `(network-log-snapshot session)` | vector of network-log entries (latest-first) |
| `downloads-snapshot` | `(downloads-snapshot session)` | vector of passively-captured downloads `{:path :suggested-filename :url :ts :size-bytes}` |
| `close!` | `(close! session)` | `:ok` — idempotent; safe on `nil` or already-closed sessions |

### `launch!`

Throws an `ex-info` with `:cognitect.anomalies/category :cognitect.anomalies/incorrect` on invalid input:

- `:profile` is required and must be a string or keyword; rejects blank names, absolute paths (`/etc/passwd`, `\Windows\...`), embedded path separators (`foo/bar`, `foo\bar`), and `..` traversal. Normalization lives in `polyllmith.browser.profile/profile-key`.
- `:headed?` must be a boolean. Default `false`.
- `:mode` must be `:read-only` or `:action`. Default `:read-only`. The driver stores this on the session map but does not enforce it — it's an intent marker for callers that gate interaction themselves.

Operations on a `nil` or closed session throw; `navigate` also throws on a blank URL, and `logged-in?` on a blank selector.

## CDP Attach

For sites with serious anti-bot protection — anything backed by Cloudflare's modern challenge stack, Akamai-class bot detection, or fingerprinting on browsing history — Playwright's bundled Chromium gets blocked even with valid session cookies because the *browser itself* lacks the legitimacy signals (browsing history, prior-visit timing, JavaScript-engine quirks) that the protection layer expects. Attaching to the user's **already-running, already-authenticated** browser via the DevTools Protocol sidesteps this entirely — the browser **is** legitimate.

```clojure
;; Start your real browser with the debug port:
;;   $ brave-browser --remote-debugging-port=9222 &
;; or chromium, or chrome — the protocol is the same.

(require '[polyllmith.browser.interface :as br])

(def s (br/connect! {:cdp-url       "http://localhost:9222"
                     :page-selector :first})) ; or :new, or a regex like #"example\.com/.*"

;; Same surface as a launch!ed session — everything works:
(br/screenshot! s "/tmp/page.png")
(br/a11y-snapshot s)
(br/navigate s "https://example.com/dashboard")

;; Disconnect-not-kill: close! detaches without closing the user's browser.
(br/close! s)
```

Returns a session map of the **same shape as `launch!`**. `close!` on an attached session closes the local `Browser` handle (severing the CDP connection) and the local `Playwright` instance, but leaves the user's actual browser, context, and page alone. The user can keep working in the same tab as if the brick was never there.

| Option | Default | Description |
|--------|---------|-------------|
| `:cdp-url` | (required) | `http(s)://` or `ws(s)://` URL of the browser's DevTools endpoint (e.g. `"http://localhost:9222"`). The driver validates the scheme. |
| `:page-selector` | `:first` | Which tab to bind to. `:first` (first existing page; opens a new blank one if context has none), `:new` (always open a fresh blank tab), a `java.util.regex.Pattern` (first page whose `.url()` matches), or a `(Page) -> bool` predicate. |
| `:mode` | `:read-only` | Intent marker, same semantics as `launch!`. |
| `:connect-timeout-ms` | `60000` | Budget in ms for `connectOverCDP` + page selection. |

**Elevated authority.** CDP attach grants meaningfully more authority than `launch!` — the bound page belongs to whatever the user is signed into in their actual browser (mail, source control, banking). Keep sessions read-only in intent unless interaction is explicitly justified, and be deliberate about what you drive.

**Forward-inference rule: SPA click ≠ `.navigate()`.** When the bound page is an authenticated SPA on a Cloudflare-protected site, `navigate` (a fresh HTTP request) still triggers the challenge even from within the warm tab, while SPA-internal navigation triggered by `click!` on an in-app link does not — it routes via a JSON fetch + client-side state update, with no fresh HTML request to gate. For detail-page scraping inside a protected SPA, click the link element rather than navigating to its URL, and press Escape between clicks if details open as a modal that intercepts subsequent clicks.

## Configuration

| Option | Default | Description |
|--------|---------|-------------|
| `:profile` | (required) | Profile name. Becomes the directory name under `:user-data-dir`. Validated against path traversal. Keyword and string inputs converge on the same normalized string key. |
| `:headed?` | `false` | Boolean. `true` opens a visible Chromium window — useful for local debugging, requires a display. `false` runs headless (the CI default). |
| `:mode` | `:read-only` | Intent marker (`:read-only` or `:action`) stored on the session map. Not enforced by the driver. |
| `:user-data-dir` | `".browser-profiles"` | Base directory for profile dirs, relative to the JVM's working directory. Each session gets `<user-data-dir>/<profile>/`. The default lands inside the worktree and is gitignored. |
| `:executable-path` | `nil` | Absolute path to a Chromium-family browser binary (e.g. `/bin/brave-browser`, system Chromium). When set, Playwright drives that binary instead of its bundled Chromium download. Must exist and be executable. |
| `:launch-timeout-ms` | `120000` | Per-launch budget (in ms) for the `launchPersistentContext` call. Cold-start of headed Chromium plus first-time profile setup can exceed the 30s steady-state executor budget; this is a launch-only override. Steady-state ops (click, screenshot, snapshot) still observe the 30s budget. |
| `:browser-args` | `[]` | Seq of strings appended as CLI args to the launched browser (Playwright's `setArgs`). Useful for `--no-sandbox` on hosts where the user-namespace sandbox is restricted, or for suppressing first-run dialogs when driving Brave (`--no-first-run --no-default-browser-check`). |

### Playwright browser cache

Playwright Java does NOT auto-download Chromium at `Playwright.create()` time. You install browsers separately via Playwright's CLI:

```bash
# One-time install of Chromium for the pinned Playwright version.
# `com.microsoft.playwright.CLI` is a Java main class — `clojure -M -m` won't
# reach it because `-M -m` only resolves Clojure namespaces. Use `java -cp`
# against the resolved classpath instead:
java -cp "$(clojure -Spath -Sdeps '{:deps {com.microsoft.playwright/playwright {:mvn/version "1.59.0"}}}')" \
  com.microsoft.playwright.CLI install chromium
```

The browser binaries land in an OS-specific cache directory:

- macOS: `~/Library/Caches/ms-playwright`
- Linux: `~/.cache/ms-playwright`
- Windows: `%USERPROFILE%\AppData\Local\ms-playwright`

Override the cache location by setting `PLAYWRIGHT_BROWSERS_PATH` before launching the JVM.

The browser install is per-host, not per-worktree — subsequent worktrees that pin the same Playwright version reuse it.

## Testing

```bash
clj -M:test:dev -n polyllmith.browser.driver-test -n polyllmith.browser.profile-test
```

Both namespaces are Tier 1 (hermetic — no Chromium needed). They cover profile-name normalization (`profile-test`), `:headed?` / `:mode` validation, and `close!` no-op paths (`driver-test`). Tests that launch real Chromium should be tagged `^:integration` so the default `bb test` stays hermetic.

## Known Limitations

- **One serialized Playwright session per call site.** Each `launch!` opens its own `Playwright` instance on a dedicated single-thread executor. There is no shared instance pool, no per-session parallelism, and no cross-session reuse. This matches Playwright Java's thread-safety guidance but caps throughput at one navigation per session at a time.
- **No captcha-solving, no stealth tech.** Vanilla Playwright defaults. Targets that fingerprint headless Chromium or gate on captchas will block. For sites with serious anti-bot detection, use [CDP Attach](#cdp-attach) against a real browser the user already authenticated in.
- **Chromium-family only for `connect!`.** Playwright Java exposes `connectOverCDP()` on the chromium browser-type only; there is no equivalent for firefox or webkit.
- **Linux sandbox restrictions.** On Ubuntu 24.04 with AppArmor's `unprivileged_userns_restriction=1`, `launchPersistentContext` hangs then dies. Workaround: pass `:browser-args ["--no-sandbox" "--no-first-run" "--no-default-browser-check"]` to `launch!`.
