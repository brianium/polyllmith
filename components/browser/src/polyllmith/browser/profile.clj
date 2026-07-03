(ns polyllmith.browser.profile
  "Profile-name normalization for the browser brick.

   Lives in its own namespace (Playwright-import-free) so both
   `polyllmith.browser.driver/launch!` and the capability-bundle session store
   (Phase 2) can call the same helper. That way `:demo` and `\"demo\"` resolve
   to the same key in any caller — there is one canonical normalization rule.

   Validation rejects anything that could escape the configured user-data-dir:
     - nil / non-string-non-keyword inputs
     - blank / empty names
     - absolute paths (starts with `/` or `\\`)
     - path separators inside the name (`/`, `\\`)
     - parent traversal (`..`)"
  (:require [clojure.string :as str]))

(defn profile-key
  "Normalize a profile name to a safe filesystem-segment string.

   Accepts strings and keywords; throws `clojure.lang.ExceptionInfo` with
   `:cognitect.anomalies/category :cognitect.anomalies/incorrect` ex-data for
   any rejected input (see the namespace docstring for the full rule set).

   For keywords the returned string is `(name kw)` — namespace is dropped.
   For strings the input is returned unchanged once it passes validation.

   Both the driver and the capability bundle delegate to this fn so that
   `(profile-key :demo)` and `(profile-key \"demo\")` collide on `\"demo\"`."
  [profile]
  (when (nil? profile)
    (throw (ex-info ":profile is required"
                    {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                     :profile profile})))
  (let [s (cond
            (keyword? profile) (name profile)
            (string? profile) profile
            :else
            (throw (ex-info ":profile must be a string or keyword"
                            {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                             :profile profile
                             :type (type profile)})))]
    (cond
      (str/blank? s)
      (throw (ex-info "Profile name cannot be blank"
                      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                       :profile profile}))

      (or (str/starts-with? s "/")
          (str/starts-with? s "\\"))
      (throw (ex-info "Profile name cannot be an absolute path"
                      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                       :profile profile}))

      (or (str/includes? s "/")
          (str/includes? s "\\"))
      (throw (ex-info "Profile name cannot contain path separators"
                      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                       :profile profile}))

      (or (= s "..")
          (str/includes? s "..")
          (= s "."))
      (throw (ex-info "Profile name cannot be '.' or contain '..'"
                      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                       :profile profile}))

      :else s)))
