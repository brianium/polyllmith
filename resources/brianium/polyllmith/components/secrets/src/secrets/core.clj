(ns {{top/ns}}.{{main/ns}}.secrets.core
  "Load secrets from .env file in development, with System/getenv as fallback.
   Re-reading the file on each call enables REPL reload without JVM restart."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]))

(defn parse-env-file
  "Parse .env file into a map. Ignores comments and blank lines.
   Returns nil if file doesn't exist."
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (->> (slurp f)
           str/split-lines
           (remove #(or (str/blank? %) (str/starts-with? % "#")))
           (map #(str/split % #"=" 2))
           (filter #(= 2 (count %)))
           (into {})))))

(defn load-secrets
  "Load secrets from .env file, with System/getenv as fallback.
   Real env vars take precedence (for production)."
  ([] (load-secrets ".env"))
  ([path]
   (merge
    (parse-env-file path)
    (into {} (System/getenv)))))

(defn get-secret
  "Get a secret value by key from a loaded secrets map."
  [secrets key]
  (get secrets key))
