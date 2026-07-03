(ns polyllmith.browser.core
  "Brick entry point. Delegates to polyllmith.browser.driver for raw Playwright
   interop; this namespace re-exports the public surface as plain pass-through
   fns, matching the components/solana-rpc shape."
  (:require [polyllmith.browser.driver :as driver]))

(defn launch!
  "Launch a profile-keyed persistent Chromium context. See driver/launch!."
  [opts]
  (driver/launch! opts))

(defn connect!
  "Attach to an already-running Chromium-family browser over the DevTools
   Protocol. Returns a session map of the same shape as launch!.
   See driver/connect!."
  [opts]
  (driver/connect! opts))

(defn navigate
  "Navigate the session's page to url. Returns the session map with :url set.
   See driver/navigate."
  [session url]
  (driver/navigate session url))

(defn new-page!
  "Open a new Page in the session's existing BrowserContext.
   Cloudflare avoidance — see driver/new-page!."
  [session opts]
  (driver/new-page! session opts))

(defn logged-in?
  "True if sentinel-selector resolves to a present element on the current page.
   See driver/logged-in?."
  [session sentinel-selector]
  (driver/logged-in? session sentinel-selector))

(defn a11y-snapshot
  "Return `{:format :playwright-aria-snapshot :snapshot <string>}` for the
   current page. See driver/a11y-snapshot."
  [session]
  (driver/a11y-snapshot session))

(defn install-popup-listener!
  "Install the K4 default-close popup listener on the session's context.
   See driver/install-popup-listener!."
  [session]
  (driver/install-popup-listener! session))

(defn install-network-listener!
  "Install page.onRequest/page.onResponse callbacks that append plain
   metadata entries to the session's bounded network-log atom.
   See driver/install-network-listener!."
  [session]
  (driver/install-network-listener! session))

(defn install-download-listener!
  "Install a page.onDownload callback that saveAs-es each download into the
   session's capture dir and appends plain metadata to the bounded downloads
   atom. See driver/install-download-listener!."
  [session]
  (driver/install-download-listener! session))

(defn page-content
  "Return the full HTML string for the session's current page.
   See driver/page-content."
  [session]
  (driver/page-content session))

(defn download-text!
  "Start a browser download from url and return its text body.
   See driver/download-text!."
  [session url]
  (driver/download-text! session url))

(defn evaluate!
  "Execute `js` (a JavaScript string) in the session's current page and return
   the deserialized result. When `args` is supplied (a vector), the head
   element is passed as the script's single argument. See driver/evaluate!."
  ([session js]
   (driver/evaluate! session js))
  ([session js args]
   (driver/evaluate! session js args)))

(defn screenshot!
  "Write a PNG screenshot of the session's current page to `path`.
   See driver/screenshot!."
  [session path]
  (driver/screenshot! session path))

(defn network-log-snapshot
  "Return the current network-log entries as a plain Clojure vector
   (latest-first). See driver/network-log-snapshot."
  [session]
  (driver/network-log-snapshot session))

(defn downloads-snapshot
  "Return the current per-session download captures as a plain Clojure vector
   (latest-first). Each entry is {:path :suggested-filename :url :ts
   :size-bytes}. See driver/downloads-snapshot."
  [session]
  (driver/downloads-snapshot session))

(defn click!
  "Click the element matching `selector` on the session's current page.
   See driver/click!."
  [session selector]
  (driver/click! session selector))

(defn type-into!
  "Fill the element matching `selector` with `text`. Routes to Playwright's
   `Locator.fill`. See driver/type-into!. Renamed to avoid collision with
   `clojure.core/type`; the capability-bundle effect ID is still
   `:polyllmith.browser.interface/type`."
  [session selector text]
  (driver/type-into! session selector text))

(defn keyboard-type!
  "Type text through the page-level keyboard into the focused element/editor.
   See driver/keyboard-type!."
  [session text]
  (driver/keyboard-type! session text))

(defn press-key!
  "Press a Playwright key string on the session's page-level keyboard.
   See driver/press-key!."
  [session key]
  (driver/press-key! session key))

(defn scroll!
  "Dispatch a mouse-wheel event with pixel deltas. See driver/scroll!."
  [session delta-x delta-y]
  (driver/scroll! session delta-x delta-y))

(defn wait-for-condition!
  "Wait for `condition` (tagged map: `:selector`, `:url-pattern`,
   `:load-state`, or `:timeout-ms`). See driver/wait-for-condition!."
  [session condition]
  (driver/wait-for-condition! session condition))

(defn grant-permissions!
  "Grant browser-context permissions, optionally scoped to origin.
   See driver/grant-permissions!."
  [session permissions origin]
  (driver/grant-permissions! session permissions origin))

(defn set-clipboard-text!
  "Set the local system clipboard to text.
   See driver/set-clipboard-text!."
  [session text]
  (driver/set-clipboard-text! session text))

(defn close!
  "Idempotently close a session. Returns :ok. See driver/close!."
  [session]
  (driver/close! session))
