(ns polyllmith.browser.profile-test
  "Tier 1 (hermetic) tests for the shared profile-key helper. Pure normalization
   — no Playwright, no filesystem. The driver's launch!-based rejection tests
   stay in driver-test as integration-flavored input checks; this namespace is
   the canonical contract for the helper itself."
  (:require [clojure.test :refer [deftest is testing]]
            [polyllmith.browser.profile :as profile]))

;; ---------------------------------------------------------------------------
;; Happy paths
;; ---------------------------------------------------------------------------

(deftest profile-key-string-passthrough
  (testing "string profiles pass through unchanged"
    (is (= "hn-reader" (profile/profile-key "hn-reader")))
    (is (= "demo" (profile/profile-key "demo")))
    (is (= "with-dashes-and-numbers-123" (profile/profile-key "with-dashes-and-numbers-123")))))

(deftest profile-key-keyword-normalization
  (testing "keyword profiles are normalized via clojure.core/name"
    (is (= "demo" (profile/profile-key :demo)))
    (is (= "hn-reader" (profile/profile-key :hn-reader)))))

(deftest profile-key-namespaced-keyword-drops-namespace
  (testing "namespaced keyword's name part is returned; the ns is dropped"
    (is (= "session" (profile/profile-key :my-app/session)))
    (is (= "demo" (profile/profile-key :polyllmith.browser/demo)))))

(deftest profile-key-string-and-keyword-converge
  (testing "the SAME normalized key results from a string vs keyword input"
    (is (= (profile/profile-key :demo)
           (profile/profile-key "demo")))
    (is (= (profile/profile-key :hn-reader)
           (profile/profile-key "hn-reader")))))

;; ---------------------------------------------------------------------------
;; Rejection paths
;; ---------------------------------------------------------------------------

(deftest profile-key-rejects-nil
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":profile is required"
                        (profile/profile-key nil))))

(deftest profile-key-rejects-blank
  (testing "empty string is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"blank"
                          (profile/profile-key ""))))
  (testing "whitespace-only string is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"blank"
                          (profile/profile-key "   ")))))

(deftest profile-key-rejects-absolute-path
  (testing "unix-style absolute path"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"absolute path"
                          (profile/profile-key "/etc/passwd"))))
  (testing "windows-style absolute path"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"absolute path"
                          (profile/profile-key "\\Windows\\System32")))))

(deftest profile-key-rejects-path-separators
  (testing "forward slash inside the name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"separator"
                          (profile/profile-key "foo/bar"))))
  (testing "back slash inside the name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"separator"
                          (profile/profile-key "foo\\bar")))))

(deftest profile-key-rejects-parent-traversal
  (testing "literal .. is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"'\.\.'"
                          (profile/profile-key ".."))))
  (testing ".. embedded in a name is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"'\.\.'"
                          (profile/profile-key "foo..bar"))))
  (testing "single . is rejected (would canonicalize to the base dir, cross-contaminating other profiles)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be '\.'"
                          (profile/profile-key ".")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be '\.'"
                          (profile/profile-key :.)))))

(deftest profile-key-rejects-non-string-non-keyword
  (testing "integer"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"string or keyword"
                          (profile/profile-key 42))))
  (testing "map"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"string or keyword"
                          (profile/profile-key {})))))

;; ---------------------------------------------------------------------------
;; Anomaly category — every rejection path carries the same anomaly
;; ---------------------------------------------------------------------------

(deftest profile-key-rejections-carry-incorrect-anomaly
  (testing "every validation failure includes :cognitect.anomalies/category :cognitect.anomalies/incorrect"
    (doseq [bad [nil "" "   " "/abs" "rel/path" ".." "foo..bar" "." 42]]
      (let [ex-data (try (profile/profile-key bad)
                         nil
                         (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :cognitect.anomalies/incorrect
               (:cognitect.anomalies/category ex-data))
            (str "input " (pr-str bad) " did not carry :incorrect anomaly"))))))
