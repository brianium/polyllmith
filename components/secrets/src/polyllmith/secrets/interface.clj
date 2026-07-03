(ns polyllmith.secrets.interface
  "Public API for secrets - environment variable management with .env file support."
  (:require [polyllmith.secrets.core :as core]))

(defn load-secrets
  "Load secrets from .env file with System/getenv fallback.
   Real env vars take precedence over .env file values.

   Usage:
     (load-secrets)           ; loads from .env in current directory
     (load-secrets \"path/to/.env\")

   Returns a map of environment variable names to values."
  ([] (core/load-secrets))
  ([path] (core/load-secrets path)))

(defn get-secret
  "Get a secret value by key from a loaded secrets map.

   Usage:
     (def secrets (load-secrets))
     (get-secret secrets \"DATABASE_URL\")"
  [secrets key]
  (core/get-secret secrets key))

(defn parse-env-file
  "Parse a .env file into a map. Ignores comments and blank lines.
   Returns nil if file doesn't exist.

   This is a lower-level function - prefer load-secrets for most uses."
  [path]
  (core/parse-env-file path))
