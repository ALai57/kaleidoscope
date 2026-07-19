(ns kaleidoscope.http-api.photo-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kaleidoscope.api.albums :as albums-api]
            [kaleidoscope.http-api.photo :as photo]
            [kaleidoscope.persistence.filesystem.in-memory-impl :as in-mem]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [matcher-combinators.test :refer [match?]]))

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

;; Task 10: :tenant (DB scope) and :asset-store (upload destination) diverge in
;; an ephemeral env — a "fixed" resolver pins :tenant to the real site
;; ("andrewslai.com") while :asset-store points at the isolated per-env bucket
;; ("ephemeral-tenant-assets"), even though the raw Host header is some
;; fly.dev-style ephemeral hostname. The upload must write bytes to the
;; isolated store while recording the DB photo row under the pinned tenant.
(deftest process-photo-upload-scopes-db-and-store-independently-test
  (let [database          (embedded-h2/fresh-db!)
        tenant-store      (atom {})
        ephemeral-store   (atom {})
        req               {:headers    {"host" "kal-eph-xyz.fly.dev"}
                            :tenant     {:hostname "andrewslai.com" :asset-store "ephemeral-tenant-assets"}
                            :components {:database                database
                                         :static-content-adapters {"andrewslai.com"          (in-mem/make-mem-fs {:store tenant-store})
                                                                    "ephemeral-tenant-assets" (in-mem/make-mem-fs {:store ephemeral-store})}
                                         :notify-image-resizer!   (fn [& _] nil)}}
        file              {:filename "myfile.png"
                            :tempfile (io/file (io/resource "public/images/lock.svg"))}]
    (photo/process-photo-upload! req file)

    (testing "The photo bytes land in the isolated asset store, not the pinned tenant's own bucket"
      (is (match? {albums-api/MEDIA-FOLDER map?} @ephemeral-store))
      (is (= {} @tenant-store)))

    (testing "The DB photo row is scoped to the pinned tenant, not the raw ephemeral Host header"
      (is (seq (albums-api/get-full-photos database {:hostname "andrewslai.com"})))
      (is (empty? (albums-api/get-full-photos database {:hostname "kal-eph-xyz.fly.dev"}))))))
