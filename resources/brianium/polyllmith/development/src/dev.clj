(ns dev
  (:require [clj-reload.core :as reload]
            [dev.browser :as dev-browser]
            [integrant.core :as ig]))

(defonce ^{:doc "The running system. Managed via start/stop/restart."}
  system nil)

(defonce ^{:doc "Last config used to start the system. Used by resume."}
  last-config nil)

(defn reload
  "Reload changed namespaces. If a system is running, suspends it first
   and resumes after reload so handlers are hot-swapped without restarting servers."
  []
  (when system
    (ig/suspend! system))
  (reload/reload)
  (when (and system last-config)
    (let [new-config (if (fn? last-config) (last-config) last-config)]
      (alter-var-root #'system (fn [old] (ig/resume (ig/expand new-config) old)))))
  :reloaded)

(defn start
  "Start a system with the given config. Configs can be merged for
   multiple subsystems:
     (start (merge app/config admin/config))
   Example: (start (requiring-resolve '{{top/ns}}.{{main/ns}}.my-base.config/config))

   If a system is already running, it is halted first to release its
   resources (ports, threads, file handles). Otherwise a previous system
   would be orphaned — its references unreachable but its sockets still
   bound — and the only way to release them would be to restart the JVM."
  [config]
  (alter-var-root #'last-config (constantly config))
  (let [c (if (fn? config) (config) config)]
    (alter-var-root #'system
                    (fn [old]
                      (when old (ig/halt! old))
                      (ig/init (ig/expand c)))))
  :started)

(defn stop
  "Stop the running system."
  []
  (when system
    (ig/halt! system)
    (alter-var-root #'system (constantly nil)))
  :stopped)

(defn restart
  "Stop, reload, and start the system with the given config."
  [config]
  (stop)
  (reload/reload)
  (start config))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; REPL browser (components/browser)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn browser-launch!
  "Launch (or relaunch) the headed REPL browser session. The session
   survives (reload) — see dev.browser."
  ([] (dev-browser/launch!))
  ([opts] (dev-browser/launch! opts)))

(defn browser
  "The current REPL browser session if it's still alive, else nil."
  []
  (dev-browser/current))

(defn browser-stop!
  "Close the REPL browser session."
  []
  (dev-browser/stop!))
