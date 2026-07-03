(ns polyllmith.browser.driver
  "Raw Playwright Java interop. This is the ONLY namespace in components/browser
   that imports com.microsoft.playwright.*. Per D7 of the browser-use north star,
   interface.clj does not expose raw Java objects as its public contract.
   Java objects stay behind opaque, namespaced/internal session keys here."
  (:require [polyllmith.browser.profile :as profile]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [com.microsoft.playwright BrowserContext BrowserContext$GrantPermissionsOptions
            Download
            Page Playwright BrowserType$LaunchPersistentContextOptions
            Page$NavigateOptions
            Page$GetByRoleOptions Page$ScreenshotOptions Locator Request Response TimeoutError]
           [com.microsoft.playwright.options AriaRole LoadState WaitUntilState]
           [java.awt Toolkit]
           [java.awt.datatransfer StringSelection]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent Executors ExecutorService Callable TimeUnit]
           [java.util.function Consumer]
           [java.util.regex Pattern]))

;; ---------------------------------------------------------------------------
;; :mode validation (safety mode, per browser-bundle Phase 1.2)
;;
;; This `:mode` is the *safety* mode and gates interaction verbs (click, type,
;; press, scroll) at the capability-bundle layer. The previous `:mode` field —
;; which selected headless vs headed — has been renamed to `:headed?`.
;; ---------------------------------------------------------------------------

(def ^:private valid-modes #{:read-only :action})

(defn- validate-mode
  "Validate that mode is one of #{:read-only :action}. Returns the mode on success."
  [mode]
  (if (contains? valid-modes mode)
    mode
    (throw (ex-info "Invalid :mode — must be :read-only or :action"
                    {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                     :mode mode
                     :valid-modes valid-modes}))))

;; ---------------------------------------------------------------------------
;; :headed? validation
;; ---------------------------------------------------------------------------

(defn- validate-headed?-boolean
  "Validate that headed? is a boolean. Returns the value on success."
  [headed?]
  (if (boolean? headed?)
    headed?
    (throw (ex-info "Invalid :headed? — must be a boolean"
                    {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                     :headed? headed?
                     :type (type headed?)}))))

(defn- validate-executable-path
  "Validate that executable-path, if provided, is a string pointing at an
   existing, executable file. Returns the value on success (or nil if unset)."
  [executable-path]
  (cond
    (nil? executable-path) nil
    (not (string? executable-path))
    (throw (ex-info "Invalid :executable-path — must be a string"
                    {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                     :executable-path executable-path
                     :type (type executable-path)}))
    :else
    (let [f (io/file executable-path)]
      (when-not (and (.exists f) (.canExecute f))
        (throw (ex-info "Invalid :executable-path — file does not exist or is not executable"
                        {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                         :executable-path executable-path
                         :exists? (.exists f)
                         :executable? (.canExecute f)})))
      executable-path)))

;; ---------------------------------------------------------------------------
;; Single-thread executor helpers
;; ---------------------------------------------------------------------------

(def ^:dynamic *executor-timeout-ms*
  "Per-call budget on-executor will wait for the executor's submitted task to
   complete before raising a TimeoutException. 30s is a sensible default for
   steady-state calls (click/type/screenshot/snapshot). Cold-start operations
   — first-time launchPersistentContext + Chromium/Brave boot + first-page
   Cloudflare gate — can legitimately exceed this. launch! binds this longer
   for the launch phase via :launch-timeout-ms (default 120s)."
  30000)

(defn- on-executor
  "Submit f as a Callable to executor, block on its result (default 30s
   timeout, overridable via *executor-timeout-ms*), return the value. Wraps
   execution exceptions so they unwrap cleanly to the caller.

   On TimeoutException, attempts to cancel the future via thread interrupt.
   Note that Playwright Java does not always honor interrupts mid-call — a
   timed-out task may continue running on the executor after .cancel returns,
   poisoning the next submission to the same session (the next call queues
   behind the still-running ghost task and observes Playwright in an
   unexpected state when the ghost finally completes). Callers should treat
   a TimeoutException from any session op as a signal to close! the session
   rather than retry."
  ^Object [^ExecutorService executor f]
  (let [fut (.submit executor ^Callable (reify Callable (call [_] (f))))]
    (try
      (.get fut (long *executor-timeout-ms*) TimeUnit/MILLISECONDS)
      (catch java.util.concurrent.ExecutionException ee
        (throw (or (.getCause ee) ee)))
      (catch java.util.concurrent.TimeoutException te
        (.cancel fut true)
        (throw te)))))

(defn- shutdown-executor!
  "Cleanly shut down the session executor. Tries graceful, then forces."
  [^ExecutorService executor]
  (try
    (.shutdown executor)
    (when-not (.awaitTermination executor 5 TimeUnit/SECONDS)
      (.shutdownNow executor))
    (catch Exception _
      (try (.shutdownNow executor) (catch Exception _ nil)))))

;; ---------------------------------------------------------------------------
;; launch!
;; ---------------------------------------------------------------------------

(defn- launch-options
  "Build the LaunchPersistentContextOptions for a context launch. Pure — it
   only constructs the Playwright builder POJO and launches nothing, so it is
   safe to exercise in hermetic (Tier 1) tests.

   Headed sessions get NoViewport: the page tracks the OS window, so resizing
   the window resizes the page. Without it Playwright pins a fixed 1280x720
   page viewport and a resized window shows the page rendered at 1280x720 with
   empty chrome (and a stranded scrollbar) around it.

   Playwright Java's NoViewport signal is an *empty* `viewportSize` Optional;
   an *unset* field (the headless default) means \"use the 1280x720 default\".
   `setViewportSize(null)` empties the Optional, and the single-arg overload is
   unambiguous (the other takes two ints). Headless keeps the default viewport
   for deterministic screenshots/tests."
  ^BrowserType$LaunchPersistentContextOptions [{:keys [headed? executable-path browser-args]}]
  (cond-> (doto (BrowserType$LaunchPersistentContextOptions.)
            (.setHeadless (not headed?))
            ;; Downloads (K7): with the persistent-context default, a
            ;; Content-Disposition:attachment click captures NOTHING — Phase 0
            ;; proved `Page.onDownload` never fires unless the context was
            ;; built with acceptDownloads=true. Not a safe default here; the
            ;; brick owns context creation for `launch!`, so set it explicitly.
            (.setAcceptDownloads true))
    headed?         (.setViewportSize nil)
    executable-path (.setExecutablePath (.toPath (io/file executable-path)))
    (seq browser-args) (.setArgs (vec browser-args))))

(defn launch!
  "Launch a profile-keyed persistent Chromium context. Returns an opaque session map.

   Public keys: {:profile profile-name :mode mode :headed? headed? :user-data-dir path}.
   Java objects (Playwright instance, BrowserContext, Page, executor) live behind
   internal namespaced keys — callers must not depend on them.

   Opts:
     :profile       (required) string or keyword profile name. Resolves to
                    <user-data-dir>/<profile>/ on disk.
     :headed?       boolean. Default false (headless). When true, Chromium opens
                    a visible window — useful for local debugging, requires a
                    display. Headed sessions use NoViewport so the page tracks
                    the OS window (resizing the window resizes the page).
                    Headless sessions keep a fixed default viewport for stable
                    screenshots/tests.
     :mode          safety mode for capability-bundle interaction gating.
                    :read-only (default) or :action. Capability-bundle
                    interaction verbs (click, type, press, scroll) reject in
                    :read-only sessions; the driver itself does not enforce this
                    — it only stores the value on the session map.
     :user-data-dir (optional) base directory for profile dirs. Defaults to
                    '.browser-profiles' relative to JVM working directory.
     :executable-path (optional) absolute path to a Chromium-family browser
                    binary (e.g. '/bin/brave-browser', system Chromium). When
                    set, Playwright uses that browser instead of its bundled
                    Chromium download. Must be an existing, executable file.
     :launch-timeout-ms (optional) integer ms budget for the launchPersistentContext
                    call. Default 120000. Cold-start of headed Brave/Chromium plus
                    first-time profile setup can exceed the 30s steady-state budget;
                    this is a launch-only override of *executor-timeout-ms*.
     :browser-args  (optional) seq of strings appended as CLI args to the launched
                    browser (e.g. ['--no-sandbox', '--no-first-run']). Useful on
                    distros where the bundled Chromium sandbox is blocked
                    (Ubuntu 24.04 + kernel.apparmor_restrict_unprivileged_userns=1)
                    and to suppress first-run dialogs when driving Brave."
  [{:keys [profile headed? mode user-data-dir executable-path launch-timeout-ms browser-args]
    :or {headed? false
         mode :read-only
         user-data-dir ".browser-profiles"
         launch-timeout-ms 120000}
    :as _opts}]
  (let [profile-name (profile/profile-key profile)
        headed? (validate-headed?-boolean headed?)
        mode (validate-mode mode)
        executable-path (validate-executable-path executable-path)
        base-dir (io/file user-data-dir)
        profile-dir (io/file base-dir profile-name)
        _ (when-not (.mkdirs profile-dir)
            (when-not (.exists profile-dir)
              (throw (ex-info "Cannot create profile directory"
                              {:cognitect.anomalies/category :cognitect.anomalies/fault
                               :user-data-dir user-data-dir
                               :profile profile-name
                               :path (.getCanonicalPath profile-dir)}))))
        ;; Per-session download capture dir (K3). Lives under the profile's
        ;; user-data-dir so captured artifacts are co-located with the profile
        ;; and cleaned up with it. `saveAs` writes durable copies here in the
        ;; onDownload callback; the bounded `::downloads` ring records the path.
        download-dir (io/file profile-dir ".downloads")
        _ (.mkdirs download-dir)
        executor (Executors/newSingleThreadExecutor)]
    (try
      (let [{:keys [playwright browser context page]}
            (binding [*executor-timeout-ms* launch-timeout-ms]
              (on-executor
               executor
               (fn []
                 (let [pw (Playwright/create)]
                   (try
                     (let [opts (launch-options {:headed? headed?
                                                 :executable-path executable-path
                                                 :browser-args browser-args})
                           ctx (.. pw chromium
                                   (launchPersistentContext (.toPath profile-dir) opts))
                           pg (.newPage ctx)]
                       {:playwright pw :browser (.browser ctx) :context ctx :page pg})
                     (catch Throwable t
                       (try (.close pw) (catch Exception _ nil))
                       (throw t)))))))]
        {:profile profile-name
         :mode mode
         :headed? headed?
         :user-data-dir (.getCanonicalPath profile-dir)
         :url nil
         ::executor executor
         ::playwright playwright
         ::browser browser
         ::context context
         ::page page
         ::closed? (atom false)
         ::cdp-attached? false
         ;; Phase 4.4: per-session network-log atom. Bounded vector of plain
         ;; Clojure metadata maps populated by `install-network-listener!`.
         ;; Lives on the session map (not the bundle's session-atom value) so
         ;; that swap! contention on the bundle atom is unrelated to log
         ;; appends.
         ::network-log (atom [])
         ;; Download capture (K3). Bounded vector of plain metadata maps
         ;; ({:path :suggested-filename :url :ts :size-bytes}) populated by
         ;; `install-download-listener!`'s `Page.onDownload` callback after it
         ;; `saveAs`-es into `::download-dir`. Same per-session-atom rationale
         ;; as `::network-log`.
         ::downloads (atom [])
         ;; Absolute path of the per-session capture dir (durable saveAs target).
         ::download-dir (.getCanonicalPath download-dir)
         ;; Set of page identity-hash ids that already have an onDownload
         ;; listener installed, so `new-page!` can't double-install on the same
         ;; page. Plain ids, NOT retained Page/Download handles.
         ::download-listener-pages (atom #{})
         ;; Cloudflare avoidance: counter of caller-intended new pages
         ;; awaiting the popup listener's `accept` callback. `new-page!`
         ;; increments before calling `.newPage`; the listener decrements
         ;; and skips the default-close. See `install-popup-listener!`.
         ::pending-new-page-count (atom 0)})
      (catch Throwable t
        (shutdown-executor! executor)
        (throw t)))))

;; ---------------------------------------------------------------------------
;; connect! — attach to an already-running Chromium-family browser via CDP
;; ---------------------------------------------------------------------------

(defn- validate-cdp-url
  "Validate that cdp-url is a string with an http(s)/ws(s) scheme. Returns the
   value on success."
  [cdp-url]
  (cond
    (not (string? cdp-url))
    (throw (ex-info "Invalid :cdp-url — must be a string"
                    {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                     :cdp-url cdp-url
                     :type (type cdp-url)}))
    (not (re-matches #"(?i)^(https?|wss?)://.+" cdp-url))
    (throw (ex-info "Invalid :cdp-url — must start with http://, https://, ws://, or wss://"
                    {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                     :cdp-url cdp-url}))
    :else cdp-url))

(defn- select-page
  "Pick a Page from the attached context, given a page-selector.

   Selectors:
     :first         — first existing page (opens a new one if none exist)
     :new           — always open a new blank page
     a regex/Pattern — first page whose .url matches the pattern
     a fn (Page)->bool — first page satisfying the predicate

   Throws ex-info :cognitect.anomalies/not-found when a regex or fn selector
   has no match. Pure: no side effects beyond `.newPage` on :first/:new."
  ^Page [page-selector ^BrowserContext context pages]
  (cond
    (= page-selector :first)
    (or (first pages) (.newPage context))

    (= page-selector :new)
    (.newPage context)

    (instance? java.util.regex.Pattern page-selector)
    (or (first (filter #(re-find page-selector (.url ^Page %)) pages))
        (throw (ex-info "No page in attached browser matches :page-selector pattern"
                        {:cognitect.anomalies/category :cognitect.anomalies/not-found
                         :page-selector (str page-selector)
                         :available-urls (mapv #(.url ^Page %) pages)})))

    (fn? page-selector)
    (or (first (filter page-selector pages))
        (throw (ex-info "No page in attached browser matches :page-selector predicate"
                        {:cognitect.anomalies/category :cognitect.anomalies/not-found
                         :available-urls (mapv #(.url ^Page %) pages)})))

    :else
    (throw (ex-info "Invalid :page-selector — must be :first, :new, a regex, or a (Page)->bool fn"
                    {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                     :page-selector page-selector
                     :type (type page-selector)}))))

(defn connect!
  "Attach to an already-running Chromium-family browser via the DevTools
   Protocol. The browser must have been started with --remote-debugging-port=N.

   Returns a session map of the same shape as launch!, with ::cdp-attached? set
   to true. close! on an attached session disconnects the Playwright client
   without closing the user's browser, page, or context.

   Opts:
     :cdp-url       (required) http://host:port or ws://host:port URL of the
                    target browser's DevTools endpoint. http(s) form is
                    typically the /json/version base URL minus the path
                    (e.g. 'http://localhost:9222').
     :page-selector (optional) one of:
                      :first  (default) — first existing page; opens a new one
                              if the context has none
                      :new    — open a new blank page in the existing context
                      a regex — pick the first page whose URL matches
                      a fn (Page)->bool — pick the first satisfying page
     :mode          safety mode (:read-only default, or :action). Stored on the
                    session map; capability-bundle verbs gate on it. Driver
                    itself does not enforce.
     :connect-timeout-ms (optional) integer ms budget for the connectOverCDP
                    + page selection. Default 60000."
  [{:keys [cdp-url page-selector mode connect-timeout-ms]
    :or {mode :read-only
         page-selector :first
         connect-timeout-ms 60000}
    :as _opts}]
  (let [cdp-url (validate-cdp-url cdp-url)
        mode (validate-mode mode)
        executor (Executors/newSingleThreadExecutor)]
    (try
      (let [{:keys [playwright browser context page]}
            (binding [*executor-timeout-ms* connect-timeout-ms]
              (on-executor
               executor
               (fn []
                 (let [pw (Playwright/create)]
                   (try
                     (let [br (-> pw .chromium (.connectOverCDP cdp-url))
                           contexts (.contexts br)
                           _ (when (empty? contexts)
                               (throw (ex-info "Attached browser has no contexts"
                                               {:cognitect.anomalies/category :cognitect.anomalies/unavailable
                                                :cdp-url cdp-url})))
                           ctx (first contexts)
                           pages (vec (.pages ctx))
                           pg (select-page page-selector ctx pages)]
                       {:playwright pw :browser br :context ctx :page pg})
                     (catch Throwable t
                       (try (.close pw) (catch Exception _ nil))
                       (throw t)))))))]
        {:profile (str "cdp:" cdp-url)
         :mode mode
         :headed? true
         :user-data-dir nil
         :url (try (.url page) (catch Exception _ nil))
         ::executor executor
         ::playwright playwright
         ::browser browser
         ::context context
         ::page page
         ::closed? (atom false)
         ::cdp-attached? true
         ::network-log (atom [])
         ;; Download capture for CDP sessions is BEST-EFFORT (K7): the brick
         ;; cannot reconfigure the attached context's download policy. We still
         ;; wire the listener (cheap/additive) and give it a tmp capture dir
         ;; (connect! has no user-data-dir to nest under).
         ::downloads (atom [])
         ::download-dir (.getCanonicalPath
                         (.toFile (Files/createTempDirectory
                                   "polyllmith-browser-downloads-"
                                   (make-array FileAttribute 0))))
         ::download-listener-pages (atom #{})
         ;; Cloudflare avoidance: counter of caller-intended new pages
         ;; awaiting the popup listener's `accept` callback.
         ::pending-new-page-count (atom 0)})
      (catch Throwable t
        (shutdown-executor! executor)
        (throw t)))))

;; ---------------------------------------------------------------------------
;; close!
;; ---------------------------------------------------------------------------

(defn close!
  "Idempotently close a session. Returns :ok. Safe on nil or already-closed sessions.

   For launch!-owned sessions, closes in order: page -> context -> playwright,
   then shuts down the executor.

   For connect!-attached sessions (::cdp-attached? = true), disconnects only:
   closes the local Browser handle (which severs the CDP connection) and the
   local Playwright instance, then shuts down the executor. The user's actual
   browser, context, and page are left alone.

   Each close step is wrapped in try/catch so a failure in one step doesn't block
   later steps. Phase 0 confirmed BrowserContext.close() and Playwright.close()
   are both idempotent, so double-close on the same session is safe."
  [session]
  (cond
    (nil? session)
    :ok

    (nil? (::context session))
    :ok

    :else
    (let [{::keys [executor playwright browser context page closed? cdp-attached?
                   download-dir]} session]
      ;; compare-and-set! atomically flips closed? from false → true on the
      ;; winning thread and returns false for every loser. This ensures
      ;; concurrent close! callers only submit work to the executor once
      ;; (and only call shutdown-executor! once). Playwright's own close is
      ;; idempotent, so the prior non-atomic check was correct in outcome —
      ;; this just avoids the duplicate executor submission.
      (when (and closed? (compare-and-set! closed? false true))
        ;; Capture a TimeoutException separately from the generic catch so it
        ;; surfaces to the caller — a timeout from close! means Playwright's
        ;; close work didn't finish on the executor thread, and the next
        ;; `shutdownNow` interrupt is unlikely to terminate it (Playwright Java
        ;; does not honour thread interrupts mid-call), leaving a zombie
        ;; browser process. The caller needs to know so they can clean up at
        ;; the process level (e.g., `pkill chromium` in tear-down).
        ;; Non-timeout exceptions stay swallowed — they're cleanup-path
        ;; failures (a partial close of one of page/context/playwright) that
        ;; the next step already tolerates.
        (let [timeout-ex (try
                           (on-executor
                            executor
                            (fn []
                              (if cdp-attached?
                                ;; Attached: disconnect-not-close. Closing the
                                ;; Browser handle severs the CDP connection but
                                ;; does NOT terminate the user's actual browser
                                ;; process. Skipping page/context .close means
                                ;; the user's tab and profile stay live.
                                (do
                                  (try (when browser (.close browser)) (catch Exception _ nil))
                                  (try (when playwright (.close playwright)) (catch Exception _ nil)))
                                ;; Owned: full teardown of page -> context -> playwright.
                                (do
                                  (try (when page (.close page)) (catch Exception _ nil))
                                  (try (when context (.close context)) (catch Exception _ nil))
                                  (try (when playwright (.close playwright)) (catch Exception _ nil))))
                              :ok))
                           nil
                           (catch java.util.concurrent.TimeoutException te te)
                           (catch Exception _ nil))]
          (shutdown-executor! executor)
          ;; Reclaim the per-session download capture dir. The capture dir is
          ;; session-scoped SCRATCH — agents retain anything they want past the
          ;; session with `download-save` (copy to a caller path); the in-memory
          ;; `::downloads` ring is gone after close anyway, so leftover files
          ;; are unreachable. This fires for BOTH session types: connect!
          ;; sessions get a standalone JVM temp dir nothing else owns, and
          ;; launch! sessions nest `.downloads/` under the PERSISTENT profile
          ;; dir (close! does NOT remove the profile — cookies/auth survive —
          ;; so without this, captures accumulate across relaunches). launch!
          ;; recreates `.downloads/` via `(.mkdirs …)` on the next launch.
          ;; Filesystem-only, so it runs off the executor.
          (when download-dir
            (try
              (doseq [^java.io.File f (reverse (file-seq (io/file download-dir)))]
                (.delete f))
              (catch Exception _ nil)))
          (when timeout-ex
            (throw timeout-ex))))
      :ok)))

;; ---------------------------------------------------------------------------
;; Session liveness guard
;; ---------------------------------------------------------------------------

(defn- ensure-live-session!
  "Throw an ex-info anomaly if session is nil or already closed.
   Otherwise return the session unchanged."
  [session op]
  (when (nil? session)
    (throw (ex-info (str "Cannot " op " a nil session")
                    {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                     :op op
                     :session nil})))
  (let [closed? (::closed? session)]
    (when (and closed? @closed?)
      (throw (ex-info (str "Cannot " op " a closed session")
                      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                       :op op
                       :profile (:profile session)}))))
  session)

;; ---------------------------------------------------------------------------
;; navigate
;; ---------------------------------------------------------------------------

(defn navigate
  "Navigate the session's page to url. Returns the session map with :url set
   to Playwright's post-navigation URL for chainability.

   Throws ex-info with :cognitect.anomalies/category :cognitect.anomalies/incorrect
   if the session is nil or already closed, or if url is blank.

   Blocks for up to 30 seconds on the session's executor. On timeout, a
   TimeoutException propagates and the underlying Playwright .navigate call
   may continue running asynchronously on the executor (Playwright Java
   does not reliably honor thread interrupts mid-call). Callers should
   treat a timeout as a terminal session failure: close! the session and
   relaunch rather than retry, otherwise the next session op queues behind
   the still-running ghost task."
  [session url]
  (ensure-live-session! session "navigate")
  (when (or (not (string? url)) (str/blank? url))
    (throw (ex-info "navigate url must be a non-blank string"
                    {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                     :url url})))
  (let [{::keys [executor page]} session
        current-url (on-executor
                     executor
                     (fn []
                       (.navigate page url
                                  (doto (Page$NavigateOptions.)
                                    (.setWaitUntil WaitUntilState/COMMIT)))
                       (.url page)))]
    (assoc session :url current-url)))

;; ---------------------------------------------------------------------------
;; Per-session download capture (download-capture P1)
;;
;; Passive capture mirroring the network-log shape (K1): the session always
;; listens; `click`/`new-page`/`navigate` produce the download unchanged.
;; In Playwright Java 1.59 there is no `BrowserContext.onDownload`, so we
;; register `Page.onDownload` on the session page (and on each `new-page!`
;; page before its navigation).
;;
;; THREADING (Phase 0, load-bearing): the onDownload callback fires on the
;; per-session executor thread ITSELF — events are buffered and drain when the
;; executor next pumps. So the blocking `saveAs` is safe directly in-callback,
;; and we must NOT re-submit it to the executor (single-thread executor →
;; self-deadlock: the callback is already a queued task on that thread). This
;; is the OPPOSITE of `install-popup-listener!`, whose `.close` IS re-submitted
;; — do not copy that pattern here.
;;
;; DURABILITY (K3): downloads are deleted when the context closes and
;; `Download.path()` is unreliable for CDP/remote sessions. We `saveAs` into
;; the per-session `::download-dir` and record THAT durable path, never
;; `path()`. The Download handle is not retained past the callback.
;; ---------------------------------------------------------------------------

;; Downloads are rarer and heavier than network events (each carries a saved
;; file on disk), so the ring is bound far smaller than MAX-LOG-ENTRIES
;; (research.md Q3: "lean small, e.g. 50").
(def ^:private MAX-DOWNLOAD-ENTRIES 50)

(defn- append-download-entry!
  "Append `entry` to the session's downloads atom, evicting the oldest entries
   once the buffer exceeds `MAX-DOWNLOAD-ENTRIES`. Single swap! — atomic.

   Like `append-log-entry!`, the trim copies into a FRESH vector with
   `(into [] (subvec ...))` rather than handing back the PersistentSubVector,
   which would retain the full backing array and silently leak captured
   entries' memory across many trims."
  [downloads-atom entry]
  (swap! downloads-atom
         (fn [v]
           (let [v' (conj v entry)
                 n (count v')]
             (if (> n MAX-DOWNLOAD-ENTRIES)
               (into [] (subvec v' (- n MAX-DOWNLOAD-ENTRIES)))
               v')))))

(def ^:private max-sanitized-filename-len
  "Cap on the sanitized-filename segment. The on-disk unique-name is
   `<nanoTime>-<identityHashCode>-<safe-name>` (~31 chars of prefix); clamping
   the name to 200 keeps the whole segment well under the 255-byte single-name
   filesystem limit. Without the cap a server-supplied `Content-Disposition`
   filename of a few hundred chars makes `saveAs` throw — and the listener's
   `(catch Throwable _t nil)` would swallow it, silently dropping the capture.
   The full original is preserved in the entry's `:suggested-filename`."
  200)

(defn- sanitize-filename
  "Reduce a download's `suggestedFilename` to a safe single path segment:
   strip any directory parts, replace anything outside [A-Za-z0-9._-] with '_',
   and clamp to `max-sanitized-filename-len` chars. Empty / nil input falls back
   to \"download\". The result is used only as the trailing segment of a saveAs
   path inside `::download-dir`, never a caller-supplied directory."
  [filename]
  (let [base (-> (or filename "")
                 ;; drop any path the server tried to smuggle into the name
                 (str/replace #"^.*[/\\]" "")
                 (str/replace #"[^A-Za-z0-9._-]" "_"))
        clamped (if (> (count base) max-sanitized-filename-len)
                  (subs base 0 max-sanitized-filename-len)
                  base)]
    (if (str/blank? clamped) "download" clamped)))

(defn- register-page-download-listener!
  "Register a `Page.onDownload` callback on `page` that `saveAs`-es each
   download into `download-dir` and appends plain metadata onto the bounded
   `downloads` ring. Idempotent per page: the page's `System/identityHashCode`
   is recorded in `listener-pages` so a second registration on the same page
   (e.g. via `new-page!` re-binding) is a no-op.

   Pure side-effect on the page/atoms — assumes it is ALREADY running on the
   per-session executor thread (both `new-page!` and `install-download-listener!`
   call it inside an `on-executor` block). The onDownload callback itself also
   fires on that executor thread (Phase 0), so the blocking `saveAs` is safe
   in-callback and is NOT re-submitted. The callback body swallows throwables so
   one bad download can't kill the event pump. No-op when any of the download
   atoms are missing (defensive for older session maps)."
  [^Page page downloads download-dir listener-pages]
  (when (and downloads download-dir listener-pages)
    (let [page-id (System/identityHashCode page)]
      (when-not (contains? @listener-pages page-id)
        (swap! listener-pages conj page-id)
        (.onDownload
         page
         ^Consumer
         (reify Consumer
           (accept [_ dl]
             (try
               (let [^Download download dl
                     suggested (.suggestedFilename download)
                     url (.url download)
                     ;; Unique segment so repeated suggested filenames can't
                     ;; overwrite an earlier capture: <nanoTime>-<id>-<name>.
                     safe-name (sanitize-filename suggested)
                     unique-name (str (System/nanoTime) "-"
                                      (System/identityHashCode download) "-"
                                      safe-name)
                     target (io/file download-dir unique-name)]
                 ;; saveAs is blocking but safe in-callback (executor thread).
                 (.saveAs download (.toPath target))
                 (append-download-entry!
                  downloads
                  {:path (.getCanonicalPath target)
                   :suggested-filename suggested
                   :url url
                   :ts (System/currentTimeMillis)
                   ;; `.length` is a long; do NOT `(int …)` it — RT/intCast
                   ;; throws ArithmeticException for files > ~2 GB, which the
                   ;; surrounding catch-all would swallow, silently dropping
                   ;; the entry while the file sits on disk. :size-bytes holds a long.
                   :size-bytes (.length target)}))
               (catch Throwable _t nil)))))))))

;; ---------------------------------------------------------------------------
;; new-page!  — Cloudflare avoidance (caller-intended new tab in same context)
;; ---------------------------------------------------------------------------

(defn new-page!
  "Open a new Page in the session's existing BrowserContext, optionally
   navigate it to `url`, and (when `select?`) make it the session's
   active page. Returns the updated session map (always — even when
   `select?` is false the returned session has fresh :url for the
   active page).

   Designed to bypass anti-bot walls (Cloudflare 'Just a moment…') that
   trigger on in-tab navigation to certain URLs. A fresh page hits the
   destination without the JS-history fingerprint that the in-tab nav
   would carry.

   Coordinates with `install-popup-listener!` via the session's
   `::pending-new-page-count` atom. The driver increments the counter
   BEFORE calling `.newPage` so the popup listener (which fires on the
   Playwright event-dispatch thread) recognizes the new page as
   caller-intended and skips its default-close behavior. The increment
   happens INSIDE the on-executor block so we don't accidentally raise
   the counter for a popup that the Playwright loop synthesizes between
   the swap! and the .newPage call.

   Opts:
     :url      (optional) string. When present, the new page is
               navigated to it before returning.
     :select?  (optional, default true) when true the session's
               ::page is rebound to the new page and any subsequent
               session ops (click, type, html, …) operate on it.
               When false the new page exists in the context but is
               not bound; callers must re-bind themselves if they want
               to interact with it (rare — left in for symmetry)."
  [session {:keys [url select?] :or {select? true}}]
  (ensure-live-session! session "new-page")
  (when (and (some? url) (or (not (string? url)) (str/blank? url)))
    (throw (ex-info "new-page :url must be nil or a non-blank string"
                    {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                     :url url})))
  (let [{::keys [executor ^BrowserContext context pending-new-page-count
                 downloads download-dir download-listener-pages]} session
        new-page (on-executor
                  executor
                  (fn []
                    ;; Reserve the popup-listener carve-out BEFORE .newPage
                    ;; — the listener fires synchronously on Playwright's
                    ;; event thread and races with the next line otherwise.
                    (swap! pending-new-page-count inc)
                    (try
                      (let [^Page pg (.newPage context)]
                        ;; Install the download listener on the new page BEFORE
                        ;; any navigation, so a download triggered by the first
                        ;; navigation/click is captured (download-capture P1).
                        ;; Idempotent per page id, so a later re-bind via
                        ;; install-download-listener! won't double-register.
                        (register-page-download-listener!
                         pg downloads download-dir download-listener-pages)
                        (when (some? url)
                          (.navigate pg url
                                     (doto (Page$NavigateOptions.)
                                       (.setWaitUntil WaitUntilState/COMMIT))))
                        pg)
                      (catch Throwable t
                        ;; On failure, release the reservation so a stray
                        ;; popup later in the session doesn't escape close.
                        (swap! pending-new-page-count
                               (fn [n] (if (pos? n) (dec n) n)))
                        (throw t)))))
        new-url (on-executor executor (fn [] (.url ^Page new-page)))]
    (if select?
      (-> session
          (assoc ::page new-page)
          (assoc :url new-url))
      ;; Caller asked NOT to select — leave session bound to old page,
      ;; but expose the new page's URL on a side key so callers can log it.
      (assoc session ::last-new-page-url new-url))))

;; ---------------------------------------------------------------------------
;; logged-in?
;; ---------------------------------------------------------------------------

(defn logged-in?
  "True if sentinel-selector resolves to a present element on the current page.
   Sentinel-selector is a CSS selector that should match a logged-in-only element
   (e.g., a profile link in the page header).

   Purely structural: calls `(.querySelector page selector)` immediately after
   navigation's `load` event fires. This is correct for server-rendered pages
   (most non-SPA sites, HN, classic forums, server-rendered admin UIs) but will
   false-negative on SPAs that inject the sentinel element after `load`. The
   north star's P3 onboarding agent is targeted at HN specifically (server-
   rendered), so the structural check is sufficient for that use case. If a
   future site needs JS-rendered sentinel support, the right shape is a
   `:wait-for-selector?` opt on this fn or a sibling `wait-for-sentinel!` —
   neither is in scope for the driver-bootstrap P1.

   Throws ex-info with :cognitect.anomalies/category :cognitect.anomalies/incorrect
   if the session is nil or already closed, or if sentinel-selector is blank."
  [session sentinel-selector]
  (ensure-live-session! session "logged-in?")
  (when (or (not (string? sentinel-selector)) (str/blank? sentinel-selector))
    (throw (ex-info "logged-in? sentinel-selector must be a non-blank string"
                    {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                     :sentinel-selector sentinel-selector})))
  (let [{::keys [executor page]} session]
    (on-executor
     executor
     (fn []
       (some? (.querySelector page sentinel-selector))))))

;; ---------------------------------------------------------------------------
;; a11y-snapshot
;;
;; Phase 0.1 selected `Page.ariaSnapshot(nil)` (returns a plain `String` like
;; `\"- button \\\"Submit\\\": Go\"` — no YAML parser required). The CDP
;; `Accessibility.getFullAXTree` branch was rejected: it produces a Gson tree
;; that would need a Gson-aware walker, and the string form is already
;; readable by both agents and humans.
;; ---------------------------------------------------------------------------

(defn a11y-snapshot
  "Capture an accessibility snapshot of the current page.

   Returns `{:format :playwright-aria-snapshot :snapshot <string>}`. The
   `:snapshot` is Playwright's own readable accessibility serialization,
   e.g. `\"- button \\\"Submit\\\": Go\"` for a page containing a button with
   aria-label \"Submit\" and inner text \"Go\". Treat the format as opaque —
   it is Playwright-internal — but role/name pairs are eyeball-readable and
   regex-greppable.

   Throws ex-info with :cognitect.anomalies/category :cognitect.anomalies/incorrect
   if the session is nil or already closed."
  [session]
  (ensure-live-session! session "a11y-snapshot")
  (let [{::keys [executor page]} session
        snapshot (on-executor
                  executor
                  (fn []
                    (.ariaSnapshot page nil)))]
    {:format :playwright-aria-snapshot
     :snapshot snapshot}))

;; ---------------------------------------------------------------------------
;; Popup listener (K4 default-close policy)
;;
;; Phase 0.6 confirmed BrowserContext.onPage(...) fires for window.open,
;; target=_blank, ctrl-click and any other path that opens a new top-level
;; page in the context. The listener accepts a java.util.function.Consumer
;; reified from Clojure. We pick BrowserContext.onPage (broader: catches
;; popups + ctrl-clicks + any new top-level page) over Page.onPopup so that
;; child-context pages also trigger the default-close hook.
;; ---------------------------------------------------------------------------

(defn install-popup-listener!
  "Install the K4 default-close popup listener on the session's context.

   Any new page that opens in the session's BrowserContext — `window.open`,
   `target=_blank`, OAuth popups, ctrl-clicks — is closed immediately,
   regardless of `:mode`. Single-tab contract is invariant; capture/follow
   is deferred to P2.5. Both `:read-only` and `:action` sessions get the
   same close-by-default behavior so action-mode flows do not silently
   accumulate orphaned pages with no agent-facing handle.

   Returns the session unchanged. Idempotent only insofar as the caller
   does not install twice; the bundle wires this in once during
   `launch-session`.

   Imports the BrowserContext/Page classes (per D7, only `driver.clj`
   speaks Playwright); the registry handler in `interface.clj` invokes
   this via the public symbol but never touches the raw classes."
  [{::keys [executor ^BrowserContext context] :as session}]
  ;; K4: brick-internal default close for every new page, regardless of
  ;; :mode. Single-tab contract is invariant — capture/follow is deferred
  ;; to P2.5.
  ;;
  ;; D1 threading contract: the registration of the listener runs on the
  ;; per-session executor (the on-executor wrapper below), but the
  ;; Consumer.accept callback BODY is invoked later on Playwright's
  ;; internal event-dispatch thread. Calling `.close` on the Page from
  ;; that thread would violate D1 (Playwright Java objects are not
  ;; thread-safe). We re-submit the `.close` work to the session executor
  ;; from inside the callback so the actual Playwright interaction stays
  ;; on the single owning thread.
  ;;
  ;; Cloudflare avoidance carve-out: `new-page!` increments the session's
  ;; `::pending-new-page-count` atom BEFORE calling `.newPage` so the
  ;; listener can recognize the new page as caller-intended and skip the
  ;; close. The callback atomically `swap-vals!`s the counter — if the
  ;; pre-swap value was positive, the listener decrements and DOES NOT
  ;; close (caller's page); otherwise it proceeds with the default-close
  ;; behavior (popup / target=_blank / ctrl-click). The counter is plain
  ;; data, so race-safety reduces to standard swap-vals! semantics.
  (let [pending-count (::pending-new-page-count session)]
    (on-executor
     executor
     (fn []
       (.onPage context
                ^Consumer
                (reify Consumer
                  (accept [_ page]
                    (let [[old-state _new-state]
                          (if pending-count
                            (swap-vals! pending-count
                                        #(if (pos? %) (dec %) %))
                            [0 0])
                          caller-intended? (and pending-count (pos? old-state))]
                      (when-not caller-intended?
                        (.submit ^ExecutorService executor
                                 ^Callable
                                 (reify Callable
                                   (call [_]
                                     (try (.close ^Page page) (catch Exception _ nil))
                                     nil))))))))))
    session))

;; ---------------------------------------------------------------------------
;; Per-session network log + listener installer (Phase 4.4)
;;
;; Plain Clojure metadata maps are pushed onto a per-session atom-backed ring
;; buffer at request/response time. We do not retain Playwright Request or
;; Response handles after the callback returns — Playwright disposes them
;; once the network event is settled, and any later `.body`/`.text` call on
;; a stale handle would throw. Body retention is deferred to P2.5; the
;; entry just notes that the body is omitted with a structured reason.
;;
;; The atom is bounded to MAX-LOG-ENTRIES via swap-and-trim. Single-atom
;; semantics — no `java.util.ArrayDeque`.
;;
;; Request/response pairing uses `System/identityHashCode` of the Request
;; captured inside the callback closure. The id is plain Long data and
;; survives the handle disposal.
;; ---------------------------------------------------------------------------

(def ^:private MAX-LOG-ENTRIES 1000)

(defn- headers->clj
  "Coerce a Playwright headers java.util.Map (String->String) into a plain
   Clojure map. Called inside the network callback while the underlying
   handle is still live; the returned map is plain data and outlives the
   handle."
  [^java.util.Map headers]
  (if (nil? headers)
    {}
    (into {} headers)))

(defn- append-log-entry!
  "Append `entry` to the session's network-log atom, evicting oldest entries
   once the buffer exceeds `MAX-LOG-ENTRIES`. Single swap! — atomic.

   NOTE: clojure.core/subvec returns a PersistentSubVector that retains the
   backing array. If we trimmed with `subvec v (- n MAX)` the underlying
   buffer would grow unbounded across many trims even though the apparent
   count was capped — K9's bounded-retention promise would silently leak
   memory. Copy into a fresh vector with `(into [] ...)` so the old buffer
   becomes GC-eligible."
  [log-atom entry]
  (swap! log-atom
         (fn [v]
           (let [v' (conj v entry)
                 n (count v')]
             (if (> n MAX-LOG-ENTRIES)
               (into [] (subvec v' (- n MAX-LOG-ENTRIES)))
               v')))))

(defn install-network-listener!
  "Install `page.onRequest` and `page.onResponse` callbacks that push plain
   Clojure metadata maps onto the session's `::network-log` atom.

   Each request callback captures the Request's `System/identityHashCode` as
   `:request-id` and stores it alongside the request entry; the matching
   response callback re-derives the same id from `(.request response)` so
   request/response pairs are correlatable in the log.

   Playwright Request/Response handles are NOT retained — only the
   coerced data (method, url, status, headers) lives on after the
   callback returns. Body capture is intentionally deferred.

   **D1 threading caveat — accepted deviation.** The Consumer.accept
   callbacks fire on Playwright's internal event-dispatch thread, not
   the per-session executor. The read-only Playwright getters used here
   (`.method`, `.url`, `.headers`, `.status`, `.request`,
   `System/identityHashCode`) are observed to be safe to call from that
   thread; the only mutation is `swap!` on the session-local network-log
   atom, which is thread-safe. We deliberately do NOT round-trip the log
   appends through the executor — doing so for every network event would
   add unbounded queue depth and serialize ~50–200 requests/page behind
   any in-flight Playwright op. If Playwright's threading model ever
   changes such that read-only getters are not callback-thread-safe,
   this comment is the breadcrumb.

   The popup listener takes the opposite stance: its callback calls the
   non-read-only `.close ^Page page` and DOES submit back to the
   executor (see `install-popup-listener!`).

   Returns the session unchanged. Must be called once at session-launch
   time on the per-session executor."
  [{::keys [executor ^Page page network-log] :as session}]
  (on-executor
   executor
   (fn []
     (.onRequest
      page
      ^Consumer
      (reify Consumer
        (accept [_ req]
          (try
            (let [^Request request req
                  entry {:phase :request
                         :request-id (System/identityHashCode request)
                         :method (.method request)
                         :url (.url request)
                         :request-headers (headers->clj (.headers request))
                         :timestamp-ms (System/currentTimeMillis)}]
              (append-log-entry! network-log entry))
            (catch Throwable _t nil)))))
     (.onResponse
      page
      ^Consumer
      (reify Consumer
        (accept [_ res]
          (try
            (let [^Response response res
                  ^Request request (.request response)
                  entry {:phase :response
                         :request-id (System/identityHashCode request)
                         :status (.status response)
                         :url (.url response)
                         :response-headers (headers->clj (.headers response))
                         :timestamp-ms (System/currentTimeMillis)}]
              (append-log-entry! network-log entry))
            (catch Throwable _t nil)))))))
  session)

;; ---------------------------------------------------------------------------
;; install-download-listener! (download-capture P1)
;;
;; Public installer wired at session-launch time (and re-applied harmlessly
;; after page re-binds). Delegates to `register-page-download-listener!` (the
;; threading/durability contract lives in that helper's section above). The
;; install runs on the per-session executor — the callback it registers ALSO
;; fires on that executor thread, so the blocking `saveAs` is in-callback and
;; never re-submitted (do NOT copy install-popup-listener!'s `.close` handoff).
;; Best-effort for connect!/CDP sessions: registered, but the brick can't
;; guarantee the attached context's download policy (K7).
;; ---------------------------------------------------------------------------

(defn install-download-listener!
  "Install a `Page.onDownload` capture callback on the session's `::page`.
   Idempotent per page (keyed on identity in `::download-listener-pages`).
   Returns the session unchanged. Run on the per-session executor."
  [{::keys [executor ^Page page downloads download-dir download-listener-pages] :as session}]
  (on-executor
   executor
   (fn []
     (register-page-download-listener! page downloads download-dir download-listener-pages)))
  session)

;; ---------------------------------------------------------------------------
;; page-content (Phase 4.1)
;; ---------------------------------------------------------------------------

(defn page-content
  "Return the full HTML string for the session's current page.

   Routes `(.content page)` through the per-session executor. Returns the
   serialized HTML string Playwright produces (the live DOM serialized
   after JS execution, not the original HTTP response body).

   Throws ex-info with :cognitect.anomalies/category :cognitect.anomalies/incorrect
   if the session is nil or already closed."
  [session]
  (ensure-live-session! session "html")
  (let [{::keys [executor ^Page page]} session]
    (on-executor
     executor
     (fn []
       (.content page)))))

(defn download-text!
  "Start an authenticated browser download from `url` in the current page and
   return its text body. Uses the session's BrowserContext cookies/profile."
  [session url]
  (ensure-live-session! session "download-text")
  (when (or (not (string? url)) (str/blank? url))
    (throw (ex-info "download-text url must be a non-blank string"
                    {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                     :url url})))
  (let [{::keys [executor ^Page page]} session]
    (on-executor
     executor
     (fn []
       (let [download (.waitForDownload
                       page
                       (reify Runnable
                         (run [_]
                           (.evaluate page
                                      "(url) => { window.location.href = url; }"
                                      url))))]
         (with-open [stream (.createReadStream download)]
           (slurp stream)))))))

;; ---------------------------------------------------------------------------
;; evaluate! — Page.evaluate(String, Object) wrapper
;;
;; Phase 4 of the upwork spec: bundled-effect `:browser/eval` for executing
;; JavaScript in the attached page context and returning a JSON-serializable
;; result. Used for framework-reactivity bypasses (Vue/React native-setter
;; dispatch) and atomic-fill flows where a single page evaluation populates
;; multiple form fields.
;;
;; Playwright's Page.evaluate accepts a JS string and a single Object arg
;; (passed to the script as a list `arguments`). The two-arity form
;; `(.evaluate page js arg)` is what we use here — when the caller passes
;; `:args [<arg-map>]`, we hand the head element as the script's argument
;; (matching the Playwright bookmarklet shape: `function(data) {...}` reads
;; the first argument as `data`). Multi-arg call shapes are not supported —
;; the caller wraps multiple args into a single map/vector at the boundary.
;; ---------------------------------------------------------------------------

(defn- playwright-coerce
  "Recursively coerce a value tree so Playwright's `Serialization` accepts it:
   - keyword keys → strings
   - `java.lang.Long` → `Integer` when in int range, else `Double`
   - other Clojure-collection types pass through unchanged shape-wise
     (Playwright handles `java.util.List` / `java.util.Map`, and Clojure
     vectors/seqs/maps implement those interfaces).

   Leaves strings, doubles, integers, booleans, dates, patterns, etc.
   untouched."
  [v]
  (cond
    (map? v)        (persistent!
                     (reduce-kv
                      (fn [acc k val]
                        (assoc! acc
                                (if (keyword? k) (name k) k)
                                (playwright-coerce val)))
                      (transient {})
                      v))
    (sequential? v) (mapv playwright-coerce v)
    (instance? Long v)
    (let [^Long n v
          lv (.longValue n)]
      (if (and (<= Integer/MIN_VALUE lv) (<= lv Integer/MAX_VALUE))
        (Integer/valueOf (int lv))
        (Double/valueOf (double lv))))
    :else v))

(defn evaluate!
  "Execute `js` (a JavaScript string — function expression or expression body
   per Playwright's Page.evaluate contract) in the session's current page and
   return Playwright's deserialized return value. When `args` is supplied
   (a vector), the head element is passed as the script's single argument.

   Throws ex-info with :cognitect.anomalies/category :cognitect.anomalies/incorrect
   if the session is nil/closed, or if `js` is blank.

   Playwright Java's `Serialization` rejects two Clojure-native shapes:
     1. Keyword map keys (`ClassCastException: Keyword cannot be cast to String`)
     2. `java.lang.Long` values (`Unsupported type of argument: <n>`) — the JVM
        default integer type. Playwright recognizes only `Integer` and `Double`
        in the number positions of its `SerializedValue` shape.
   To make the `[{:x 10 :y 32}]`-style call shape ergonomic from idiomatic
   Clojure, the head arg is walked once:
     - keyword keys → strings (via `clojure.walk/stringify-keys`)
     - `Long` values → `Integer` when within int range, else `Double`
   Already-string keys / already-`Integer`-or-`Double` numbers are unchanged."
  ([session js]
   (evaluate! session js nil))
  ([session js args]
   (ensure-live-session! session "eval")
   (when (or (not (string? js)) (str/blank? js))
     (throw (ex-info "evaluate! :js must be a non-blank string"
                     {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                      :js-type (type js)})))
   (let [{::keys [executor ^Page page]} session
         raw-arg (when (and (vector? args) (seq args)) (first args))
         arg (when (some? raw-arg) (playwright-coerce raw-arg))]
     (on-executor
      executor
      (fn []
        (if (some? arg)
          (.evaluate page js arg)
          (.evaluate page js)))))))

;; ---------------------------------------------------------------------------
;; screenshot! (Phase 4.3)
;; ---------------------------------------------------------------------------

(defn screenshot!
  "Write a PNG screenshot of the session's current page to `path` (an
   absolute filesystem path). Returns the absolute path string Playwright
   wrote to (which is `path` itself once normalized through `io/file`).

   The caller is responsible for resolving the path and creating its
   parent directory. The capability-bundle effect handler in
   `interface.clj` does that resolution; this fn only does the
   Playwright-side call.

   Throws ex-info with :cognitect.anomalies/category :cognitect.anomalies/incorrect
   if the session is nil or already closed, or if `path` is blank."
  [session path]
  (ensure-live-session! session "screenshot")
  (when (or (not (string? path)) (str/blank? path))
    (throw (ex-info "screenshot! path must be a non-blank string"
                    {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                     :path path})))
  (let [{::keys [executor ^Page page]} session
        file (io/file path)]
    (on-executor
     executor
     (fn []
       (.screenshot page
                    (doto (Page$ScreenshotOptions.)
                      (.setPath (.toPath file))))
       (.getCanonicalPath file)))))

;; ---------------------------------------------------------------------------
;; network-log accessor (Phase 4.4)
;; ---------------------------------------------------------------------------

(defn network-log-snapshot
  "Return the current contents of the session's network-log atom as a plain
   Clojure vector. Latest-first — the most-recent event is at index 0.

   The atom itself stays inside `driver.clj` (per D7 — Playwright-adjacent
   internals stay here); the bundle's network-trace handler calls this
   accessor and applies filtering/limiting on plain Clojure data."
  [session]
  (ensure-live-session! session "network-trace")
  (let [log-atom (::network-log session)]
    (vec (reverse @log-atom))))

;; ---------------------------------------------------------------------------
;; downloads accessor (download-capture P1)
;; ---------------------------------------------------------------------------

(defn downloads-snapshot
  "Return the current contents of the session's `::downloads` atom as a plain
   Clojure vector. Latest-first — the most-recent capture is at index 0. Each
   entry is plain metadata: {:path :suggested-filename :url :ts :size-bytes}
   where `:path` is the durable saveAs copy in the session's capture dir.

   Mirrors `network-log-snapshot`: the atom stays inside `driver.clj` (D7); the
   bundle's `downloads` effect calls this accessor over plain Clojure data."
  [session]
  (ensure-live-session! session "downloads")
  (let [downloads-atom (::downloads session)]
    (vec (reverse @downloads-atom))))

;; ---------------------------------------------------------------------------
;; Selector translation
;;
;; Translate a tagged-map selector into a Playwright Locator. Inputs are one
;; of:
;;
;;   {:role <string-or-keyword> :name <string>}  -> Page.getByRole(AriaRole, opts)
;;   {:text <string>}                            -> Page.getByText(text)
;;   {:css  <string>}                            -> Page.locator(css)
;;
;; Exactly one branch must be present. Empty maps, multi-branch maps, and
;; unknown roles all throw an :incorrect anomaly. Validation happens BEFORE
;; the call is dispatched to the session executor — `translate-selector` is
;; itself called on the executor thread (Playwright Page method calls
;; require it), but the validation step is data-only.
;; ---------------------------------------------------------------------------

(defn- role->aria-role
  "Map a lowercase role string or keyword (e.g. \"button\", :menu-item) to the
   matching `com.microsoft.playwright.options.AriaRole` enum constant
   (`BUTTON`, `MENUITEM`). Throws :incorrect on unknown roles."
  ^AriaRole [role]
  (let [s (cond
            (keyword? role) (name role)
            (string? role) role
            :else (throw (ex-info "selector :role must be a string or keyword"
                                  {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                                   :role role
                                   :type (type role)})))
        ;; AriaRole constants are upper-case, dash-stripped (BUTTON, MENUITEM,
        ;; not MENU-ITEM or MENU_ITEM). Normalize the input to match.
        enum-name (-> s
                      (str/replace "-" "")
                      (str/replace "_" "")
                      str/upper-case)]
    (try
      (AriaRole/valueOf enum-name)
      (catch IllegalArgumentException _
        (throw (ex-info (str "Unknown ARIA role: " role)
                        {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                         :role role
                         :enum-attempt enum-name}))))))

(defn- translate-selector
  "Translate a tagged-map selector into a Playwright Locator. Must be invoked
   on the session executor (it calls Page methods directly)."
  ^Locator [^Page page selector]
  (when-not (map? selector)
    (throw (ex-info "selector must be a tagged map"
                    {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                     :selector selector
                     :type (type selector)})))
  (let [branches (select-keys selector [:role :text :css])
        present (set (keys branches))
        ;; :name is only valid when :role is present; treat it as
        ;; "qualifier of the :role branch" not an independent branch.
        extras (-> selector
                   (dissoc :role :text :css :name)
                   keys)]
    (when (seq extras)
      (throw (ex-info "selector has unrecognized keys"
                      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                       :selector selector
                       :unrecognized extras})))
    (when (not= 1 (count present))
      (throw (ex-info "selector must specify exactly one of :role :text :css"
                      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                       :selector selector
                       :branches present})))
    (cond
      (:role selector)
      ;; K3: :name is REQUIRED alongside :role. Role-only matching would
      ;; resolve to the first element with that role on the page — far too
      ;; broad for an agent-facing selector. The bundle schema also rejects
      ;; role-without-name; this check is defense-in-depth for direct
      ;; driver-fn callers.
      (let [n (:name selector)]
        (when-not (string? n)
          (throw (ex-info "selector :role branch requires :name (string)"
                          {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                           :selector selector})))
        (let [aria (role->aria-role (:role selector))
              opts (doto (Page$GetByRoleOptions.) (.setName ^String n))]
          (.getByRole page aria opts)))

      (:text selector)
      (.getByText page ^String (:text selector))

      (:css selector)
      ;; K3: CSS only. Playwright's `Page.locator(String)` accepts any of
      ;; its selector engines via a prefix (`xpath=...`, `text=...`,
      ;; `role=...`) and also auto-detects raw XPath when the string
      ;; starts with `/` or `(`. Reject those up front so the `:css`
      ;; branch can't be used to smuggle XPath or other engines past the
      ;; tagged-map schema.
      (let [css ^String (:css selector)
            css-trim (str/triml css)]
        (when (or (re-find #"(?i)^(xpath|text|role|nth|internal|css)=" css-trim)
                  (str/starts-with? css-trim "/")
                  (str/starts-with? css-trim "("))
          (throw (ex-info (str "selector :css must be a CSS expression — "
                               "Playwright engine prefixes (xpath=, text=, role=, ...) "
                               "and raw XPath (`/...`, `(...`) are rejected")
                          {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                           :selector selector
                           :css css})))
        (.locator page css)))))

;; ---------------------------------------------------------------------------
;; Interaction primitives — click!, type-into!, keyboard-type!, press-key!, scroll!
;;
;; Each routes through the per-session executor and is a no-mode-check thin
;; wrapper around Playwright. The capability bundle layer in
;; `polyllmith.browser.interface` enforces `:read-only` rejection before
;; calling these primitives.
;; ---------------------------------------------------------------------------

(defn click!
  "Click the element matching `selector` on the session's current page.
   Selector shapes: `{:role <role> :name <name>}`, `{:text <text>}`,
   `{:css <css>}`. Returns `:ok`.

   Throws ex-info with :cognitect.anomalies/category :cognitect.anomalies/incorrect
   if the session is nil/closed or the selector is malformed."
  [session selector]
  (ensure-live-session! session "click")
  (let [{::keys [executor page]} session]
    (on-executor
     executor
     (fn []
       (let [loc (translate-selector page selector)]
         (.click loc)
         :ok)))))

(defn type-into!
  "Fill the element matching `selector` with `text` on the session's current
   page. Routes to Playwright's `Locator.fill` — idempotent set-value
   behavior (clears the field first, then sets). Renamed to avoid the
   `clojure.core/type` collision; the capability-bundle effect ID remains
   `:polyllmith.browser.interface/type` for agent-facing surface.

   Returns `:ok`."
  [session selector text]
  (ensure-live-session! session "type")
  (when-not (string? text)
    (throw (ex-info "type! text must be a string"
                    {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                     :text text
                     :type (type text)})))
  (let [{::keys [executor page]} session]
    (on-executor
     executor
     (fn []
       (let [loc (translate-selector page selector)]
         (.fill loc text)
         :ok)))))

(defn keyboard-type!
  "Type `text` through the page-level keyboard into the currently focused
   element/editor. Unlike `type-into!`, this does not take a selector and does
   not clear an existing field first. This is needed for canvas/editor surfaces
   such as Google Docs and Google Sheets where the focused application editor
   owns text input but is not a normal fillable `<input>` / `<textarea>`.

   Returns `:ok`."
  [session text]
  (ensure-live-session! session "keyboard-type")
  (when-not (string? text)
    (throw (ex-info "keyboard-type! text must be a string"
                    {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                     :text text
                     :type (type text)})))
  (let [{::keys [executor ^Page page]} session]
    (on-executor
     executor
     (fn []
       (.. page keyboard (type text))
       :ok))))

(defn press-key!
  "Press a Playwright key string (`\"Enter\"`, `\"Tab\"`, `\"Control+A\"`, etc.)
   on the session's page-level keyboard. Returns `:ok`."
  [session key]
  (ensure-live-session! session "press")
  (when (or (not (string? key)) (str/blank? key))
    (throw (ex-info "press! key must be a non-blank string"
                    {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                     :key key})))
  (let [{::keys [executor ^Page page]} session]
    (on-executor
     executor
     (fn []
       (.. page keyboard (press key))
       :ok))))

(defn scroll!
  "Dispatch a mouse-wheel event on the session's page with the given pixel
   deltas. Routes to Playwright's `Mouse.wheel`. Returns `:ok`."
  [session delta-x delta-y]
  (ensure-live-session! session "scroll")
  (when-not (and (number? delta-x) (number? delta-y))
    (throw (ex-info "scroll! :delta-x and :delta-y must be numbers"
                    {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                     :delta-x delta-x
                     :delta-y delta-y})))
  (let [{::keys [executor ^Page page]} session
        dx (double delta-x)
        dy (double delta-y)]
    (on-executor
     executor
     (fn []
       (.. page mouse (wheel dx dy))
       :ok))))

;; ---------------------------------------------------------------------------
;; wait-for-condition!
;;
;; Read-side; no mode check. Accepts a tagged-map condition matching K10's
;; resolution: regex pattern for :url-pattern, LoadState enum for
;; :load-state, integer for :timeout-ms. `TimeoutError` is wrapped as an
;; `:unavailable` anomaly so handler code surfaces it through the same
;; failure path as other Playwright-side flakes.
;; ---------------------------------------------------------------------------

(def ^:private load-state-enums
  {:load                  LoadState/LOAD
   :domcontentloaded      LoadState/DOMCONTENTLOADED
   :networkidle           LoadState/NETWORKIDLE})

(defn- validate-condition
  "Pre-check the condition map shape before dispatching to the executor.
   Returns a `[branch value]` pair on success; throws :incorrect on a
   missing branch, multiple branches, or an unknown :load-state value."
  [condition]
  (when-not (map? condition)
    (throw (ex-info "wait-for :condition must be a tagged map"
                    {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                     :condition condition
                     :type (type condition)})))
  (let [branches (select-keys condition [:selector :url-pattern :load-state :timeout-ms])
        present (keys branches)]
    (when (not= 1 (count present))
      (throw (ex-info "wait-for :condition must specify exactly one of :selector :url-pattern :load-state :timeout-ms"
                      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                       :condition condition
                       :branches (set present)})))
    (cond
      (:selector condition)     [:selector (:selector condition)]
      (:url-pattern condition)
      (let [p (:url-pattern condition)]
        ;; Type check FIRST. The earlier `(and (string? p) ...)` form
        ;; silently let non-strings (keyword, nil, int) clear the length
        ;; guard and reach `Pattern/compile ^String value` inside
        ;; on-executor — surfacing a raw NPE/CCE instead of an :incorrect
        ;; anomaly. The bundle schema catches non-strings at dispatch
        ;; time, but direct callers of wait-for-condition! are unprotected
        ;; without this driver-side guard.
        (when-not (string? p)
          (throw (ex-info "wait-for :url-pattern must be a string"
                          {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                           :op :wait-for
                           :url-pattern p
                           :url-pattern-type (type p)})))
        (when (> (count p) 250)
          ;; Parallel to polyllmith.browser.interface/url-pattern-max-length —
          ;; bound ReDoS exposure on agent-supplied regex before Pattern/compile.
          ;; This call runs inside on-executor (30s timeout) so the cap is
          ;; defense-in-depth rather than primary, but consistency with
          ;; network-trace is worth the parallel guard.
          (throw (ex-info "wait-for :url-pattern is too long (max 250 chars to bound ReDoS exposure)"
                          {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                           :op :wait-for
                           :url-pattern-length (count p)
                           :url-pattern-max-length 250})))
        [:url-pattern p])
      (:load-state condition)
      (let [state (:load-state condition)]
        (when-not (contains? load-state-enums state)
          (throw (ex-info "wait-for :load-state must be one of #{:load :domcontentloaded :networkidle}"
                          {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                           :load-state state
                           :valid (set (keys load-state-enums))})))
        [:load-state state])
      (:timeout-ms condition)
      (let [ms (:timeout-ms condition)]
        ;; Validate type+lower-bound FIRST. The earlier ordering put the
        ;; upper-bound check first guarded by `(and (integer? ms) ...)`
        ;; which silently short-circuited for non-integer inputs (e.g.
        ;; 50000.5 cleared the upper-bound check and then failed with
        ;; the generic "must be a non-negative integer" message instead
        ;; of the over-cap message). Swap the order so each guard
        ;; produces the message it claims.
        (when-not (and (integer? ms) (>= ms 0))
          (throw (ex-info "wait-for :timeout-ms must be a non-negative integer"
                          {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                           :timeout-ms ms})))
        ;; Upper-bound check re-added after the round-7 order-swap
        ;; accidentally dropped it. Aligned with on-executor's 30s
        ;; wall-clock cap; the bundle schema also rejects > 30000 at
        ;; dispatch time, but direct callers of wait-for-condition! rely
        ;; on this driver-side guard for defense-in-depth.
        (when (> ms 30000)
          (throw (ex-info "wait-for :timeout-ms cannot exceed 30000 (per-session executor cap)"
                          {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                           :timeout-ms ms
                           :max-ms 30000})))
        [:timeout-ms ms]))))

(defn wait-for-condition!
  "Wait for `condition` on the session's page. Returns `:ok`.

   Condition shapes:
     {:selector <css>}        — wait for selector to be attached
     {:url-pattern <re-str>}  — wait for URL to match the regex
     {:load-state <state>}    — :load | :domcontentloaded | :networkidle
     {:timeout-ms <int>}      — sleep for that many ms

   Wraps `com.microsoft.playwright.TimeoutError` as ex-info with
   :cognitect.anomalies/category :cognitect.anomalies/unavailable."
  [session condition]
  (ensure-live-session! session "wait-for")
  (let [[branch value] (validate-condition condition)
        {::keys [executor ^Page page]} session]
    (try
      (on-executor
       executor
       (fn []
         (case branch
           :selector
           (.waitForSelector page ^String value)

           :url-pattern
           ;; Wrap PatternSyntaxException as an :incorrect anomaly. Raw
           ;; throw would surface a Java exception type the agent
           ;; rubric doesn't recognize.
           (.waitForURL page
                        (try (Pattern/compile ^String value)
                             (catch java.util.regex.PatternSyntaxException e
                               (throw (ex-info "wait-for :url-pattern is not a valid regex"
                                               {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                                                :op :wait-for
                                                :url-pattern value
                                                :regex-error (.getMessage e)}
                                               e)))))

           :load-state
           (.waitForLoadState page (get load-state-enums value))

           :timeout-ms
           (.waitForTimeout page (double value)))
         :ok))
      (catch TimeoutError te
        (throw (ex-info "browser/wait-for timed out"
                        {:cognitect.anomalies/category :cognitect.anomalies/unavailable
                         :op :wait-for
                         :profile (:profile session)
                         :condition condition
                         :source :playwright}
                        te)))
      ;; `on-executor` enforces a 30s wall-clock cap that throws
      ;; java.util.concurrent.TimeoutException — distinct from Playwright's
      ;; com.microsoft.playwright.TimeoutError. A caller passing
      ;; {:timeout-ms 45000} would otherwise get a raw TimeoutException
      ;; instead of the documented :unavailable anomaly. Wrap both as the
      ;; same agent-facing failure mode; :source distinguishes the path for
      ;; the rare debugging case.
      (catch java.util.concurrent.TimeoutException te
        (throw (ex-info "browser/wait-for exceeded the per-session executor cap (30s)"
                        {:cognitect.anomalies/category :cognitect.anomalies/unavailable
                         :op :wait-for
                         :profile (:profile session)
                         :condition condition
                         :source :executor-cap
                         :executor-cap-ms 30000}
                        te))))))

(defn grant-permissions!
  "Grant browser-context permissions, optionally scoped to origin."
  [session permissions origin]
  (ensure-live-session! session "grant-permissions")
  (when-not (and (sequential? permissions)
                 (seq permissions)
                 (every? #(and (string? %) (not (str/blank? %))) permissions))
    (throw (ex-info "grant-permissions requires a non-empty seq of permission strings"
                    {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                     :permissions permissions})))
  (when (and (some? origin)
             (or (not (string? origin)) (str/blank? origin)))
    (throw (ex-info "grant-permissions :origin must be nil or a non-blank string"
                    {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                     :origin origin})))
  (let [{::keys [executor ^BrowserContext context]} session]
    (on-executor
     executor
     (fn []
       (let [perms (vec permissions)]
         (if (some? origin)
           (.grantPermissions context perms
                              (doto (BrowserContext$GrantPermissionsOptions.)
                                (.setOrigin origin)))
           (.grantPermissions context perms))
         :ok)))))

(defn set-clipboard-text!
  "Set the local system clipboard to text for subsequent browser paste."
  [session text]
  (ensure-live-session! session "set-clipboard-text")
  (when-not (string? text)
    (throw (ex-info "set-clipboard-text requires a string"
                    {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                     :text text
                     :type (type text)})))
  (let [{::keys [executor]} session]
    (on-executor
     executor
     (fn []
       (let [clipboard (.getSystemClipboard (Toolkit/getDefaultToolkit))]
         (.setContents clipboard (StringSelection. text) nil)
         :ok)))))
