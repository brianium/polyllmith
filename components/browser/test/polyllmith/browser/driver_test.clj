(ns polyllmith.browser.driver-test
  "Tier 1 (hermetic) tests for the browser driver. These tests MUST NOT launch
   Chromium — they cover input validation and the no-op paths of `close!`.
   Browser-launching tests live in the ^:integration namespace.

   Profile-name normalization tests live in `polyllmith.browser.profile-test`
   (the helper is shared with the capability bundle). Here we only cover the
   driver-level surface: launch! input validation and close! no-op paths."
  (:require [clojure.test :refer [deftest is testing]]
            [polyllmith.browser.driver :as driver]))

;; ---------------------------------------------------------------------------
;; close! idempotency / nil-safety
;;
;; These hit code paths that exit before any Playwright work is submitted,
;; so they are safe to run in Tier 1.
;; ---------------------------------------------------------------------------

(deftest close!-on-nil-returns-ok
  (is (= :ok (driver/close! nil))))

(deftest close!-on-map-with-nil-context-returns-ok
  (testing "session map missing the driver-internal context key is treated as
            already-closed.  Uses the fully-qualified keyword (not the ::alias
            form) so the test doesn't silently break if the [..  :as driver]
            require alias is ever renamed — without the explicit ns, a missing
            alias would expand to a different namespace, the stub session
            would have a different key, and close! would fall through to the
            nil-session path. The test would still PASS, masking the
            regression."
    (is (= :ok (driver/close! {})))
    (is (= :ok (driver/close! {:polyllmith.browser.driver/context nil})))
    (is (= :ok (driver/close! {:profile "x"
                               :headed? false
                               :mode :read-only
                               :polyllmith.browser.driver/context nil})))))

;; ---------------------------------------------------------------------------
;; launch! input validation
;;
;; launch! validates the profile and mode/headed? fields BEFORE any Playwright
;; work, so invalid inputs throw synchronously without launching Chromium.
;; ---------------------------------------------------------------------------

(deftest launch!-rejects-nil-profile
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":profile is required"
                        (driver/launch! {}))))

(deftest launch!-rejects-blank-profile
  (testing "empty string is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"blank"
                          (driver/launch! {:profile ""}))))
  (testing "whitespace-only string is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"blank"
                          (driver/launch! {:profile "   "})))))

(deftest launch!-rejects-absolute-path
  (testing "unix-style absolute path"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"absolute path"
                          (driver/launch! {:profile "/etc/passwd"}))))
  (testing "windows-style absolute path"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"absolute path"
                          (driver/launch! {:profile "\\Windows\\System32"})))))

(deftest launch!-rejects-path-separators
  (testing "forward slash inside the name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"separator"
                          (driver/launch! {:profile "foo/bar"}))))
  (testing "back slash inside the name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"separator"
                          (driver/launch! {:profile "foo\\bar"})))))

(deftest launch!-rejects-parent-traversal
  (testing "literal ..  is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"\.\."
                          (driver/launch! {:profile ".."}))))
  (testing ".. embedded in a name is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"\.\."
                          (driver/launch! {:profile "foo..bar"})))))

(deftest launch!-rejects-non-string-non-keyword-profile
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"string or keyword"
                        (driver/launch! {:profile 42}))))

(deftest launch!-rejects-non-boolean-headed?
  (testing "string :headed? is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":headed\?"
                          (driver/launch! {:profile "x" :headed? "true"}))))
  (testing "keyword :headed? is rejected (no enum form)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":headed\?"
                          (driver/launch! {:profile "x" :headed? :headless})))))

;; ---------------------------------------------------------------------------
;; launch-options viewport wiring
;;
;; `launch-options` only constructs the Playwright builder POJO — it launches
;; no browser — so it is safe in Tier 1. Headed sessions must emit NoViewport
;; (an *empty* viewportSize Optional) so the page tracks the OS window; headless
;; must leave the field *unset* (null) so Playwright uses its 1280x720 default.
;; ---------------------------------------------------------------------------

(defn- viewport-size-field
  "Read the raw `viewportSize` field off a LaunchPersistentContextOptions.
   Unset → null; NoViewport → an empty Optional."
  [opts]
  (.get (.getField com.microsoft.playwright.BrowserType$LaunchPersistentContextOptions
                   "viewportSize")
        opts))

(deftest launch-options-headed-uses-no-viewport
  (testing "headed emits an empty viewportSize Optional (NoViewport)"
    (let [v (viewport-size-field (#'driver/launch-options {:headed? true}))]
      (is (instance? java.util.Optional v))
      (is (not (.isPresent ^java.util.Optional v)))))
  (testing "headless leaves viewportSize unset so the default 1280x720 applies"
    (is (nil? (viewport-size-field (#'driver/launch-options {:headed? false})))))
  (testing "headed flag also drives headless toggle"
    (is (false? (.headless (#'driver/launch-options {:headed? true}))))
    (is (true? (.headless (#'driver/launch-options {:headed? false}))))))

;; ---------------------------------------------------------------------------
;; launch-options acceptDownloads wiring (download-capture P1, K7)
;;
;; Phase 0 proved Page.onDownload never fires unless the persistent context was
;; built with acceptDownloads=true — it is NOT a safe default. launch-options
;; must set it so the always-on download listener actually captures.
;; ---------------------------------------------------------------------------

(defn- accept-downloads-field
  "Read the raw `acceptDownloads` field off a LaunchPersistentContextOptions."
  [opts]
  (.get (.getField com.microsoft.playwright.BrowserType$LaunchPersistentContextOptions
                   "acceptDownloads")
        opts))

(deftest launch-options-enables-accept-downloads
  (testing "acceptDownloads is true regardless of headed?/headless"
    (is (true? (accept-downloads-field (#'driver/launch-options {:headed? false}))))
    (is (true? (accept-downloads-field (#'driver/launch-options {:headed? true}))))))

;; ---------------------------------------------------------------------------
;; append-download-entry! ring-bound eviction (download-capture P1)
;;
;; Pure-fn seam on a plain atom — no Playwright, Tier 1. Mirrors the
;; network-log MAX-LOG-ENTRIES bound at the smaller MAX-DOWNLOAD-ENTRIES (50).
;; ---------------------------------------------------------------------------

(deftest append-download-entry!-evicts-oldest-past-bound
  (let [append! @#'driver/append-download-entry!
        cap @#'driver/MAX-DOWNLOAD-ENTRIES
        a (atom [])]
    (testing "appends below the bound retain everything in insertion order"
      (doseq [i (range cap)]
        (append! a {:n i}))
      (is (= cap (count @a)))
      (is (= {:n 0} (first @a)))
      (is (= {:n (dec cap)} (last @a))))
    (testing "exceeding the bound evicts the oldest, keeping the most-recent cap entries"
      (append! a {:n cap})
      (is (= cap (count @a)) "count stays clamped at the cap")
      (is (= {:n 1} (first @a)) "the oldest entry (:n 0) was evicted")
      (is (= {:n cap} (last @a)) "the newest entry is retained"))
    (testing "the trimmed buffer is a fresh PersistentVector, not a PersistentSubVector
              (subvec would retain the backing array and leak memory)"
      (is (= clojure.lang.PersistentVector (class @a))))))

(deftest sanitize-filename-strips-clamps-and-falls-back
  (let [sanitize @#'driver/sanitize-filename
        cap @#'driver/max-sanitized-filename-len]
    (testing "strips directory parts and non-safe chars"
      (is (= "report.csv" (sanitize "report.csv")))
      (is (= "report.csv" (sanitize "/etc/evil/report.csv")))
      (is (= "a_b_c.csv" (sanitize "a b c.csv"))))
    (testing "empty / nil falls back to 'download'"
      (is (= "download" (sanitize nil)))
      (is (= "download" (sanitize ""))))
    (testing "clamps an over-long name so saveAs can't overflow the 255-byte name limit"
      ;; without the clamp, a several-hundred-char Content-Disposition filename
      ;; makes saveAs throw and the listener's catch-all silently drops the capture
      (let [long-name (apply str (repeat 500 "a"))
            out (sanitize long-name)]
        (is (= cap (count out)))
        (is (<= (count out) cap))))))

(deftest launch!-rejects-invalid-mode
  (testing "non-keyword :mode is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":mode"
                          (driver/launch! {:profile "x" :mode "read-only"}))))
  (testing "unrecognized keyword :mode is rejected (only :read-only and :action are valid)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":mode"
                          (driver/launch! {:profile "x" :mode :stealth})))
    (testing "the old P1 :headless value is rejected — it lives on :headed? now"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":mode"
                            (driver/launch! {:profile "x" :mode :headless}))))))

;; ---------------------------------------------------------------------------
;; connect! input validation — pure, no Playwright work happens before
;; validate-cdp-url and validate-mode throw on bad input.
;; ---------------------------------------------------------------------------

(deftest connect!-rejects-missing-cdp-url
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":cdp-url"
                        (driver/connect! {}))))

(deftest connect!-rejects-non-string-cdp-url
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":cdp-url"
                        (driver/connect! {:cdp-url 9222})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":cdp-url"
                        (driver/connect! {:cdp-url :localhost-9222}))))

(deftest connect!-rejects-missing-scheme
  (testing "bare host:port is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":cdp-url"
                          (driver/connect! {:cdp-url "localhost:9222"}))))
  (testing "scheme-less // is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":cdp-url"
                          (driver/connect! {:cdp-url "//localhost:9222"}))))
  (testing "unsupported scheme is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":cdp-url"
                          (driver/connect! {:cdp-url "tcp://localhost:9222"})))))

(deftest connect!-rejects-invalid-mode
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":mode"
                        (driver/connect! {:cdp-url "http://localhost:9222"
                                          :mode :stealth}))))

;; ---------------------------------------------------------------------------
;; select-page — purely decides which Page to return from the pages list.
;; Reify a stub Page interface with a deterministic .url() so the tests
;; never touch real Playwright machinery.
;; ---------------------------------------------------------------------------

(defn- stub-page
  "Reify a minimal com.microsoft.playwright.Page that only implements `.url()`.
   select-page only ever calls .url on the pages it inspects."
  [^String url]
  (reify com.microsoft.playwright.Page
    (url [_] url)))

(deftest select-page-:first-returns-first-existing-page
  (let [p1 (stub-page "https://a.example/one")
        p2 (stub-page "https://a.example/two")
        out (#'driver/select-page :first nil [p1 p2])]
    (is (identical? p1 out))))

(deftest select-page-regex-matches-first-url
  (let [p1 (stub-page "https://example.com/landing")
        p2 (stub-page "https://example.com/dashboard/payments")
        p3 (stub-page "https://example.com/messages")
        out (#'driver/select-page #"/payments" nil [p1 p2 p3])]
    (is (identical? p2 out))))

(deftest select-page-regex-no-match-throws-not-found
  (let [p1 (stub-page "https://example.com/a")
        p2 (stub-page "https://example.com/b")]
    (try
      (#'driver/select-page #"/zzz" nil [p1 p2])
      (is false "expected :not-found to be thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= :cognitect.anomalies/not-found
                 (:cognitect.anomalies/category data)))
          (is (= ["https://example.com/a" "https://example.com/b"]
                 (:available-urls data))))))))

(deftest select-page-fn-matches-first-satisfying-page
  (let [p1 (stub-page "https://a.example/one")
        p2 (stub-page "https://b.example/two")
        p3 (stub-page "https://c.example/three")
        out (#'driver/select-page (fn [page]
                                    (re-find #"^https://b\." (.url page)))
                                  nil
                                  [p1 p2 p3])]
    (is (identical? p2 out))))

(deftest select-page-fn-no-match-throws-not-found
  (let [p1 (stub-page "https://example.com/a")]
    (try
      (#'driver/select-page (constantly false) nil [p1])
      (is false "expected :not-found to be thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :cognitect.anomalies/not-found
               (:cognitect.anomalies/category (ex-data e))))))))

(deftest select-page-invalid-selector-throws-incorrect
  (testing "an unrecognized keyword is rejected as :incorrect"
    (try
      (#'driver/select-page :all nil [])
      (is false "expected :incorrect to be thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :cognitect.anomalies/incorrect
               (:cognitect.anomalies/category (ex-data e)))))))
  (testing "a number is rejected"
    (try
      (#'driver/select-page 42 nil [])
      (is false "expected :incorrect to be thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :cognitect.anomalies/incorrect
               (:cognitect.anomalies/category (ex-data e))))))))

;; ---------------------------------------------------------------------------
;; close! on attached session uses the disconnect-not-close branch.
;; We can drive the cond in close! with a stub session map (no real Playwright
;; resources) because the branch only reads ::cdp-attached?, ::executor (must
;; be non-nil to enter the cond), and ::browser/::playwright (both nilable).
;; ---------------------------------------------------------------------------

(deftest close!-on-attached-stub-runs-disconnect-branch
  (testing "stub session with ::cdp-attached? true + ::context non-nil
            exercises the disconnect branch; with all close-targets nil
            the branch reduces to a no-op and returns :ok."
    (let [stub {:profile "cdp:stub"
                :polyllmith.browser.driver/context :sentinel-not-a-real-ctx
                :polyllmith.browser.driver/executor
                (java.util.concurrent.Executors/newSingleThreadExecutor)
                :polyllmith.browser.driver/closed? (atom false)
                :polyllmith.browser.driver/cdp-attached? true
                :polyllmith.browser.driver/browser nil
                :polyllmith.browser.driver/playwright nil
                :polyllmith.browser.driver/page nil}]
      (is (= :ok (driver/close! stub)))
      (is (true? @(:polyllmith.browser.driver/closed? stub))))))
