(ns kaleidoscope.persistence.filesystem.local-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.persistence.filesystem :as fs]
            [kaleidoscope.persistence.filesystem.local :as local]
            [taoensso.timbre :as log])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level :error
      (f))))

(defn- temp-dir!
  []
  (str (Files/createTempDirectory "local-fs-test" (make-array FileAttribute 0))))

;; Verified exploitable 2026-07-03 (see PLAN.md): put-file/get-file/ls built
;; their target path via bare string formatting with no confinement check —
;; an attacker-influenced path containing `../` segments (reachable via a
;; malicious upload filename, see the get-file-extension fix in
;; http_api/photo.clj) could write or read outside the configured storage
;; root entirely.
(deftest confined-path-test
  (let [root (temp-dir!)]
    (testing "A path under the root is confined"
      (is (true? (local/confined-path? root "subdir/file.txt")))
      (is (true? (local/confined-path? root "file.txt"))))

    (testing "A path that escapes the root via .. is not confined"
      (is (false? (local/confined-path? root "../escaped.txt")))
      (is (false? (local/confined-path? root "subdir/../../escaped.txt"))))))

(deftest put-file-refuses-path-traversal-test
  (let [root    (temp-dir!)
        outside (temp-dir!)
        store   (local/map->LocalFS {:root root})]
    (testing "put-file rejects a path that would write outside the root"
      (is (thrown-with-msg? Exception #"outside the storage root"
                            (fs/put-file store
                                         (str "../" (.getName (io/file outside)) "/evil.txt")
                                         (io/input-stream (.getBytes "PWNED"))
                                         nil)))
      (is (not (.exists (io/file outside "evil.txt")))))

    (testing "put-file still works normally for a confined path"
      (fs/put-file store "ok.txt" (io/input-stream (.getBytes "fine")) nil)
      (is (.exists (io/file root "ok.txt"))))))

(deftest get-file-refuses-path-traversal-test
  (let [root    (temp-dir!)
        outside (temp-dir!)
        _       (spit (str outside "/secret.txt") "TOP SECRET")
        store   (local/map->LocalFS {:root root})]
    (testing "get-file rejects a path that would read outside the root"
      (is (thrown-with-msg? Exception #"outside the storage root"
                            (fs/get-file store
                                         (str "../" (.getName (io/file outside)) "/secret.txt")
                                         {}))))))
