(ns kaleidoscope.utils.local-files-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.utils.local-files :as local-files]
            [taoensso.timbre :as log])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level :error
      (f))))

(defn- temp-dir!
  []
  (str (Files/createTempDirectory "local-files-test" (make-array FileAttribute 0))))

;; Verified exploitable 2026-07-03: before this allowlist existed, any
;; authenticated writer could point local-paths/workspace-roots at any file
;; the server process could read (e.g. /etc/passwd, a deployed .env), and
;; the contents would be spliced verbatim into an LLM prompt and returned to
;; them via the score rationale — see PLAN.md.
(deftest path-allowed-deny-by-default-test
  (testing "With no allowlist configured, nothing is allowed — not even a real, harmless directory"
    (with-redefs [local-files/configured-roots-string (fn [] nil)]
      (is (empty? (local-files/allowed-roots)))
      (is (false? (local-files/path-allowed? (System/getProperty "java.io.tmpdir"))))
      (is (false? (local-files/path-allowed? "/etc/passwd"))))))

(deftest path-allowed-with-configured-roots-test
  (let [allowed-dir   (temp-dir!)
        sensitive-dir (temp-dir!)]
    (with-redefs [local-files/configured-roots-string (fn [] allowed-dir)]
      (testing "A path under the allowed root is permitted"
        (is (true? (local-files/path-allowed? allowed-dir)))
        (is (true? (local-files/path-allowed? (str allowed-dir "/subdir/file.clj")))))

      (testing "A path outside the allowed root is refused, even though it exists on disk"
        (is (false? (local-files/path-allowed? sensitive-dir))))

      (testing "Sensitive, well-known system paths are refused"
        (is (false? (local-files/path-allowed? "/etc/passwd")))
        (is (false? (local-files/path-allowed? "/")))))))

(deftest read-local-paths-refuses-disallowed-paths-test
  (let [allowed-dir (temp-dir!)
        allowed-file (str allowed-dir "/readme.md")
        outside-file (str (temp-dir!) "/secret.txt")]
    (spit allowed-file "hello from an allowed file")
    (spit outside-file "TOP SECRET — should never be read")
    (with-redefs [local-files/configured-roots-string (fn [] allowed-dir)]
      (testing "A file under the allowed root is actually read"
        (let [result (local-files/read-local-paths [allowed-file])]
          (is (= 1 (count (:files result))))
          (is (= "hello from an allowed file" (:content (first (:files result)))))))

      (testing "A file outside the allowed root is refused, not read, regardless of what called read-local-paths"
        (let [result (local-files/read-local-paths [outside-file])]
          (is (empty? (:files result)))
          (is (= [{:path outside-file :reason :not-allowed}] (:skipped result))))))))
