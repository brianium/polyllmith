(ns polyllmith.secrets.interface-test
  (:require [clojure.test :refer [deftest testing is]]
            [polyllmith.secrets.interface :as secrets]))

(deftest parse-env-file-test
  (testing "parses valid .env file"
    (let [tmp (java.io.File/createTempFile "test" ".env")]
      (try
        (spit tmp "FOO=bar\nBAZ=qux\n")
        (is (= {"FOO" "bar" "BAZ" "qux"}
               (secrets/parse-env-file (.getPath tmp))))
        (finally
          (.delete tmp)))))

  (testing "ignores comments and blank lines"
    (let [tmp (java.io.File/createTempFile "test" ".env")]
      (try
        (spit tmp "# This is a comment\nFOO=bar\n\n# Another comment\nBAZ=qux\n")
        (is (= {"FOO" "bar" "BAZ" "qux"}
               (secrets/parse-env-file (.getPath tmp))))
        (finally
          (.delete tmp)))))

  (testing "handles values with equals signs"
    (let [tmp (java.io.File/createTempFile "test" ".env")]
      (try
        (spit tmp "DATABASE_URL=postgres://user:pass@host/db?foo=bar")
        (is (= {"DATABASE_URL" "postgres://user:pass@host/db?foo=bar"}
               (secrets/parse-env-file (.getPath tmp))))
        (finally
          (.delete tmp)))))

  (testing "returns nil for non-existent file"
    (is (nil? (secrets/parse-env-file "/nonexistent/path/.env")))))

(deftest load-secrets-test
  (testing "loads from .env file with env var override"
    (let [tmp (java.io.File/createTempFile "test" ".env")]
      (try
        (spit tmp "MY_TEST_VAR=from-file")
        (let [secrets (secrets/load-secrets (.getPath tmp))]
          ;; File value present
          (is (= "from-file" (get secrets "MY_TEST_VAR")))
          ;; System env vars also present
          (is (string? (get secrets "PATH"))))
        (finally
          (.delete tmp)))))

  (testing "system env vars take precedence"
    (let [tmp (java.io.File/createTempFile "test" ".env")]
      (try
        ;; PATH is always set in system env
        (spit tmp "PATH=/fake/path")
        (let [secrets (secrets/load-secrets (.getPath tmp))]
          ;; System PATH should win over file PATH
          (is (not= "/fake/path" (get secrets "PATH"))))
        (finally
          (.delete tmp))))))

(deftest get-secret-test
  (testing "retrieves secret by key"
    (let [secrets {"FOO" "bar" "BAZ" "qux"}]
      (is (= "bar" (secrets/get-secret secrets "FOO")))
      (is (= "qux" (secrets/get-secret secrets "BAZ")))
      (is (nil? (secrets/get-secret secrets "MISSING"))))))
