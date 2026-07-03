(ns kaleidoscope.http-api.photo-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.http-api.photo :as photo]))

;; Verified exploitable 2026-07-03 (see PLAN.md): the previous
;; implementation (`(last (str/split path #"\."))`) returned the *entire*
;; filename when it contained no `.` at all, and could still return a
;; slash-containing tail for filenames with multiple `.`s. This value is
;; spliced directly into a storage path, so a malicious upload filename
;; could redirect where the file gets written on disk (see
;; persistence/filesystem/local.clj's confinement check, added as a second,
;; independent layer of defense against the same class of bug).
(deftest get-file-extension-test
  (testing "A normal filename extracts its extension"
    (is (= "jpg" (photo/get-file-extension "photo.jpg")))
    (is (= "jpeg" (photo/get-file-extension "my.photo.jpeg"))))

  (testing "A filename with no dot doesn't leak the whole filename as the extension"
    (is (not= "../../../../etc/cron.d/evil" (photo/get-file-extension "../../../../etc/cron.d/evil")))
    (is (= "bin" (photo/get-file-extension "../../../../etc/cron.d/evil"))))

  (testing "A filename engineered to produce a slash-containing tail is rejected, not passed through"
    (is (not (re-find #"/" (photo/get-file-extension "a.b/../../etc/passwd")))))

  (testing "An overly long or symbol-laden suffix falls back to a safe default"
    (is (= "bin" (photo/get-file-extension "file.this-is-not-a-safe-extension")))
    (is (= "bin" (photo/get-file-extension "file.")))
    (is (= "bin" (photo/get-file-extension "")))))
