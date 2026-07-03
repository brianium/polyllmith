(ns polyllmith.browser.interface
  "Public interface for the browser brick. One-line pass-throughs to
   polyllmith.browser.core. Java objects from Playwright stay opaque behind
   internal session keys — callers depend on the public keys (:profile,
   :mode, :headed?, :user-data-dir, :url) only."
  (:require [polyllmith.browser.core :as core]))

;; ---------------------------------------------------------------------------
;; Public pass-through functions (driver primitives)
;; ---------------------------------------------------------------------------

(defn launch!
  "Launch a profile-keyed persistent Chromium context. Returns an opaque session map.

   Public keys: {:profile profile-name :mode mode :headed? headed?
                 :user-data-dir path :url nil-until-navigated}.

   Opts:
     :profile       (required) string or keyword profile name. Resolves to
                    <user-data-dir>/<profile>/ on disk.
     :headed?       boolean. Default false (headless). When true, Chromium
                    opens a visible window — useful for local debugging,
                    requires a display. Headed sessions use NoViewport so the
                    page tracks the OS window (resizing the window resizes the
                    page); headless keeps a fixed default viewport for stable
                    screenshots/tests.
     :mode          intent marker: :read-only (default) or :action. The
                    driver does not enforce this — it only stores the value
                    on the session map for callers that want to gate
                    interaction (click, type, press, scroll) themselves.
     :user-data-dir (optional) base directory for profile dirs. Defaults to
                    '.browser-profiles' relative to JVM working directory."
  [opts]
  (core/launch! opts))

(defn connect!
  "Attach to an already-running Chromium-family browser over the DevTools
   Protocol. Returns an opaque session map of the same shape as `launch!`
   except `:profile` is `\"cdp:<url>\"` and `:user-data-dir` is nil.

   The target browser must have been launched with `--remote-debugging-port=N`.
   `close!` on the returned session disconnects without closing the user's
   browser, page, or context.

   Opts:
     :cdp-url       (required) http(s)/ws(s) URL of the browser's DevTools
                    endpoint (e.g. \"http://localhost:9222\").
     :page-selector (optional) which page to bind: :first (default — first
                    existing page, opens a new one if none), :new (always
                    open a fresh blank page), a regex (first page whose URL
                    matches), or a fn (Page)->bool predicate.
     :mode          intent marker (:read-only default or :action). Stored on
                    the session map; the driver does not enforce.
     :connect-timeout-ms (optional) integer ms budget for the
                    connectOverCDP + page selection. Default 60000."
  [opts]
  (core/connect! opts))

(defn navigate
  "Navigate the session's page to url. Returns the session map with :url set
   to Playwright's post-navigation URL for chainability.

   Throws if the session is nil/closed or url is blank."
  [session url]
  (core/navigate session url))

(defn new-page!
  "Open a new Page in the session's existing BrowserContext, optionally
   navigate it to `:url`, and (when `:select?` — default true) rebind the
   session's active page to it. Returns the updated session map. See
   polyllmith.browser.core/new-page!.

   Designed to bypass anti-bot walls (Cloudflare 'Just a moment…') that
   trigger on in-tab navigation to certain URLs (Upwork `/apply/`)."
  [session opts]
  (core/new-page! session opts))

(defn logged-in?
  "True if sentinel-selector resolves to a present element on the current page.
   Sentinel-selector is a CSS selector that should match a logged-in-only element.

   Throws if the session is nil/closed or sentinel-selector is blank."
  [session sentinel-selector]
  (core/logged-in? session sentinel-selector))

(defn a11y-snapshot
  "Capture a Playwright ariaSnapshot of the current page.

   Returns `{:format :playwright-aria-snapshot :snapshot <string>}` where
   `:snapshot` is Playwright's readable accessibility serialization, e.g.
   `\"- button \\\"Submit\\\": Go\"`. Role/name pairs are eyeball-readable
   and regex-greppable; treat the surface format as opaque (it is
   Playwright-internal).

   Throws if the session is nil/closed."
  [session]
  (core/a11y-snapshot session))

(defn click!
  "Click the element matching `selector` on the session's current page.
   See polyllmith.browser.core/click!."
  [session selector]
  (core/click! session selector))

(defn type-into!
  "Fill the element matching `selector` with `text`. Routes to Playwright's
   idempotent `Locator.fill`. Named to avoid the `clojure.core/type` collision."
  [session selector text]
  (core/type-into! session selector text))

(defn keyboard-type!
  "Type text through the page-level keyboard into the current focused element
   or editor. This is the non-selector companion to `type-into!` for app
   surfaces such as Docs/Sheets editors."
  [session text]
  (core/keyboard-type! session text))

(defn press-key!
  "Press a Playwright key string (`\"Enter\"`, `\"Tab\"`, `\"Control+A\"`, ...)
   on the session's page-level keyboard."
  [session key]
  (core/press-key! session key))

(defn scroll!
  "Dispatch a mouse-wheel event with pixel deltas."
  [session delta-x delta-y]
  (core/scroll! session delta-x delta-y))

(defn wait-for-condition!
  "Wait for `condition` (one of `{:selector ...}`, `{:url-pattern ...}`,
   `{:load-state ...}`, `{:timeout-ms ...}`). Wraps
   `com.microsoft.playwright.TimeoutError` as an `:unavailable` anomaly."
  [session condition]
  (core/wait-for-condition! session condition))

(defn grant-permissions!
  "Grant browser-context permissions, optionally scoped to an origin."
  [session permissions origin]
  (core/grant-permissions! session permissions origin))

(defn set-clipboard-text!
  "Set the local system clipboard to text for subsequent browser paste."
  [session text]
  (core/set-clipboard-text! session text))

(defn page-content
  "Return the full HTML string for the session's current page (the live DOM
   serialized after JS execution, not the original HTTP response body)."
  [session]
  (core/page-content session))

(defn download-text!
  "Start a browser download from `url` in the current session and return the
   downloaded text body. Uses the session's authenticated BrowserContext."
  [session url]
  (core/download-text! session url))

(defn evaluate!
  "Execute `js` (a JavaScript string) in the session's current page and
   return the deserialized result. When `args` is supplied (a vector),
   the head element is passed as the script's single argument.
   See polyllmith.browser.core/evaluate!."
  ([session js] (core/evaluate! session js))
  ([session js args] (core/evaluate! session js args)))

(defn screenshot!
  "Write a PNG screenshot of the session's current page to `path`. Returns
   the absolute path string the file was written to."
  [session path]
  (core/screenshot! session path))

(defn network-log-snapshot
  "Return the current per-session network-log entries as a plain Clojure
   vector (latest-first). See polyllmith.browser.driver/network-log-snapshot."
  [session]
  (core/network-log-snapshot session))

(defn downloads-snapshot
  "Return the current per-session download captures as a plain Clojure vector
   (latest-first). Each entry is {:path :suggested-filename :url :ts
   :size-bytes}, where :path is the durable saveAs copy in the session capture
   dir. See polyllmith.browser.driver/downloads-snapshot."
  [session]
  (core/downloads-snapshot session))

(defn close!
  "Idempotently close a session. Returns :ok. Safe on nil or already-closed sessions."
  [session]
  (core/close! session))
