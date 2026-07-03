(ns dev.browser
  "REPL-driven browser session for UI testing via `components/browser`.

   The session handle lives in a `defonce` atom so it SURVIVES `(reload)`.
   clj-reload preserves `defonce` values (that's also how `dev/system`
   survives a reload); by contrast an interactively-def'd var typed at the
   REPL — `(def sess (browser/launch! ...))` — is unloaded when reload
   cascades to the `dev` namespace and re-loads it from source, orphaning the
   Playwright window. Hold the session here and reach it via `(dev/browser)`
   instead, and reloads keep the same window.

   Note: a reload does NOT re-navigate the open page, so after editing
   server-rendered HTML/CSS, re-navigate to pick up the change:
   `(browser/navigate (dev.browser/current) url)`."
  (:require [{{top/ns}}.{{main/ns}}.browser.interface :as browser]))

(defonce ^{:doc "The REPL browser session, preserved across (reload)."}
  session
  (atom nil))

(defn alive?
  "True when `s` is a session whose page still responds. Probes via a public
   read, so a window the user closed by hand (the ✕) reads as dead rather
   than lingering as a stale handle."
  [s]
  (boolean
   (and s
        (try (browser/page-content s) true
             (catch Throwable _ false)))))

(defn stop!
  "Close the held session (if any) and clear the holder. Defensive: a dead /
   already-closed session won't throw."
  []
  (when-let [s @session]
    (try (browser/close! s) (catch Throwable _ nil)))
  (reset! session nil)
  :stopped)

(defn launch!
  "Launch a fresh headed session for REPL UI work and stash it in the
   reload-surviving holder. Closes any prior session first so the profile
   lock is released. Opts merge over headed `:action` defaults; pass
   `:profile` to pick the on-disk profile dir.

     (dev.browser/launch!)
     (dev.browser/launch! {:profile \"my-app\"})"
  ([] (launch! {}))
  ([opts]
   (stop!)
   (let [s (browser/launch! (merge {:profile       "repl-ui"
                                    :headed?       true
                                    :mode          :action}
                                   opts))]
     (reset! session s)
     s)))

(defn current
  "The held browser session if it's still alive, else nil. Use this every
   time you need the session — never cache it in an interactive `def`."
  []
  (let [s @session]
    (when (alive? s) s)))
