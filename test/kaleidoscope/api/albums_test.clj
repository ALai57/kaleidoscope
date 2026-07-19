(ns kaleidoscope.api.albums-test
  (:require [kaleidoscope.api.albums :as albums-api]
            [kaleidoscope.persistence.tenant :as tenant]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.persistence.rdbms.embedded-postgres-impl :as embedded-postgres]
            [kaleidoscope.persistence.filesystem.in-memory-impl :as in-mem]
            [kaleidoscope.utils.core :as util]
            [kaleidoscope.utils.core :as u]
            [kaleidoscope.test-main :as tm]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]
            [clojure.java.io :as io]
            [kaleidoscope.test-utils :as tu]))

(use-fixtures :each
              (fn [f]
                (try
                  (log/with-min-level tm/*test-log-level*
                                      (f))
                  ;; Close each test's embedded Postgres so its SysV shm segment
                  ;; is reclaimed (macOS shmmni=32 otherwise starves initdb).
                  (finally
                    (embedded-postgres/close-open-dbs!)))))

(def example-album
  {:album-name     "Test album"
   :description    "My description"
   :hostname       "andrewslai.com"
   :cover-photo-id #uuid "d947c6b0-679f-4067-9747-3d282833a27d"})

(deftest albums-are-site-scoped-test
  ;; albums used to have no tenancy at all — GET /albums returned every
  ;; site's albums. They are now hostname-scoped like the rest of the CMS.
  (let [db (embedded-h2/fresh-db!)
        _  (albums-api/create-album! db {:album-name "andrew-album" :hostname "andrewslai.com"})
        _  (albums-api/create-album! db {:album-name "caheri-album" :hostname "caheriaguilar.com"})]
    (testing "a scoped handle confines albums to its own site"
      (let [andrew (albums-api/get-albums (tenant/scope db "andrewslai.com"))]
        (is (seq andrew))
        (is (every? #(= "andrewslai.com" (:hostname %)) andrew))
        (is (not-any? #(= "caheri-album" (:album-name %)) andrew))))
    (testing "the other site sees only its own"
      (is (match? [{:album-name "caheri-album" :hostname "caheriaguilar.com"}]
                  (albums-api/get-albums (tenant/scope db "caheriaguilar.com")))))))

(def example-photo
  {:id       #uuid "88c0f460-01c7-4051-a549-f7f123f6acc2"
   :hostname "andrewslai.com"})

(def example-photo-version
  {:photo-id       #uuid "f3c84f81-4c9f-42c0-9e68-c4aeedf7cae4" ;; From db-seed
   :hostname       "andrewslai.com"
   :path           "some/path"
   :filename       "100x100.jpeg"
   :image-category "thumbnail"})

(deftest create-and-retrieve-album-test
  (let [database (embedded-h2/fresh-db!)]
    (testing "3 example-albums seeded in DB"
      ;; Migrations seed db now for convenience
      (is (= 3 (count (albums-api/get-albums database)))))

    (let [[{:keys [id]}] (albums-api/create-album! database example-album)]
      (testing "Insert the example-album"
        (is (uuid? id)))

      (testing "Can retrieve example-album from the DB"
        (is (match? [example-album] (albums-api/get-albums database (select-keys example-album [:album-name]))))
        (is (match? [example-album] (albums-api/get-albums database {:id id}))))

      (testing "Cannot update an album via the wrong site"
        (is (nil? (first (albums-api/update-album! database "caheriaguilar.com"
                                                   {:id id :album-name "Hijacked"}))))
        (is (match? [{:album-name "Test album"}]
                    (albums-api/get-albums database {:id id}))))

      (testing "Can update an album on its own site"
        (albums-api/update-album! database "andrewslai.com"
                                  {:id         id
                                   :album-name "Something new"})
        (is (match? [{:album-name "Something new"}]
                    (albums-api/get-albums database {:id id})))))))

(deftest album-contents-test
  ;; 3 example-albums seeded in DB
  (let [database (embedded-h2/fresh-db!)
        album-id (:id (first (albums-api/get-albums database)))
        photo-ids (map :id (albums-api/get-photos database))]

    (testing "No contents in the album to start"
      (is (= [] (albums-api/get-album-contents database {:album-id album-id}))))

    (testing "Content gets added to the album"
      (let [album-content (albums-api/add-photos-to-album! database album-id photo-ids)]
        (is (= 3 (count album-content)))
        (is (vector? album-content))
        (is (= 3 (count (albums-api/get-album-contents database {:album-id album-id}))))

        (testing "Content is deleted"
          (albums-api/remove-content-album-link! database album-id (map :id album-content))
          (is (= 0 (count (albums-api/get-album-contents database {:album-id album-id})))))))))

(deftest remove-content-album-link-scoping-test
  ;; Fixes the TODO already left in http_api/album.clj ("This would allow
  ;; a user to delete contents from an album that is different from the
  ;; path specified") — a content-id belonging to a *different* album than
  ;; the one named in the request should not be deletable via that request.
  (let [database   (embedded-h2/fresh-db!)
        [album-a
         album-b]  (map :id (albums-api/get-albums database))
        photo-id   (:id (first (albums-api/get-photos database)))
        [content]  (albums-api/add-photos-to-album! database album-a photo-id)]

    (testing "A content-id belonging to a different album is not removed"
      (albums-api/remove-content-album-link! database album-b (:id content))
      (is (= 1 (count (albums-api/get-album-contents database {:album-id album-a})))))

    (testing "The content-id is removed when the correct album-id is given"
      (albums-api/remove-content-album-link! database album-a (:id content))
      (is (= 0 (count (albums-api/get-album-contents database {:album-id album-a})))))))

(deftest get-album-contents-test
  ;; 3 example-albums seeded in DB
  (let [database (embedded-h2/fresh-db!)
        album-ids (map :id (albums-api/get-albums database))
        photo-ids (map :id (albums-api/get-photos database))]

    (testing "No contents in the album to start"
      (is (= [] (albums-api/get-album-contents database))))

    (testing "Content gets added to different albums"
      (let [album-content (mapcat (partial albums-api/add-photos-to-album! database)
                                  album-ids
                                  photo-ids)]
        (is (= 3 (count album-content)))
        (testing "Get multiple contents"
          (is (= 3 (count (albums-api/get-album-contents database {:album-content-id (set (map :id album-content))})))))))

    (testing "Contents come from different albums"
      (is (= 3 (count (set (map :album-name (albums-api/get-album-contents database)))))))))

(deftest create-and-retrieve-photo-test
  (let [database (embedded-h2/fresh-db!)]
    (testing "example-photos were seeded into the DB"
      ;; Migrations seed db now for convenience
      (is (= 3 (count (albums-api/get-photos database)))))

    (let [resp (albums-api/create-photo! database example-photo)]
      (testing "Insert the example-photo"
        (is (match? {:id uuid?}
                    resp)))

      (testing "Can retrieve example-photo from the DB"
        (is (match? [example-photo] (albums-api/get-photos database {:id #uuid "88c0f460-01c7-4051-a549-f7f123f6acc2"}))))

      ;; update-photo! scopes by hostname (see PLAN.md, cross-site photo
      ;; IDOR fix). example-photo has no :hostname (nil) — SQL `column =
      ;; NULL` never matches, including against a genuinely NULL column
      ;; (real HTTP requests always carry a real Host header, so this is a
      ;; test-only edge case, not a production path) — so this test
      ;; exercises a photo created with an explicit hostname instead.
      (let [hosted (albums-api/create-photo! database (assoc example-photo
                                                              :id (random-uuid)
                                                              :hostname "andrewslai.com"))]
        (testing "Update the photo with the matching hostname"
          (is (match? {:id uuid? :photo-title "New title"}
                      (albums-api/update-photo! database (:id hosted) "andrewslai.com"
                                                {:photo-title "New title"}))))

        (testing "Update the photo with a different hostname does not match"
          (is (nil? (albums-api/update-photo! database (:id hosted) "sahiltalkingcents.com"
                                              {:photo-title "Hijacked"})))))))
  )

(deftest create-and-retrieve-photo-version-test
  (let [database (embedded-h2/fresh-db!)]
    (testing "example-photos were seeded into the DB"
      ;; Migrations seed db now for convenience
      (is (= 6 (count (albums-api/get-photo-versions database)))))

    (testing "Insert the example-photo"
      (is (match? {:id uuid?}
                  (albums-api/create-photo-version! database example-photo-version))))

    (testing "Can retrieve example-photo from the DB"
      (is (match? [example-photo-version] (albums-api/get-photo-versions database {:photo-id #uuid "f3c84f81-4c9f-42c0-9e68-c4aeedf7cae4"}))))))

(deftest retrieve-full-photos-test
  (let [database (embedded-h2/fresh-db!)]
    (testing "example-photos and versions were seeded into the DB"
      ;; Migrations seed db now for convenience
      (is (match?
            [{:id             #uuid "4a3db5ec-358c-4e36-9f19-3e0193001ff4"
              :image-category "thumbnail"
              :path           "media/processed/1d675bdc-e6ec-4522-8920-4950d33d4eee-500.jpg"}
             {:id             #uuid "4a3db5ec-358c-4e36-9f19-3e0193001ff4"
              :image-category "raw"
              :path           "media/processed/1d675bdc-e6ec-4522-8920-4950d33d4eee-500.jpg"}
             {:id             #uuid "4a3db5ec-358c-4e36-9f19-3e0193001ff4"
              :image-category "thumbnail"
              :path           "media/processed/20210422_134816 (2)-500.jpg"}
             {:id             #uuid "4a3db5ec-358c-4e36-9f19-3e0193001ff4"
              :image-category "raw"
              :path           "media/processed/20210422_134816 (2)-500.jpg"}
             {:id             #uuid "4a3db5ec-358c-4e36-9f19-3e0193001ff4"
              :image-category "thumbnail"
              :path           "media/processed/20210422_134824 (2)-500.jpg"}
             {:id             #uuid "4a3db5ec-358c-4e36-9f19-3e0193001ff4"
              :image-category "raw"
              :path           "media/processed/20210422_134824 (2)-500.jpg"}]
            (albums-api/get-full-photos database {:id #uuid "4a3db5ec-358c-4e36-9f19-3e0193001ff4"}))))))

(def UUID-REGEX
  "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

(deftest create-photo-version--test
  (let [database (embedded-h2/fresh-db!)
        mock-fs (atom {})]
    (testing "Create photo version"
      (is (match? [{:id uuid?}]
                  (albums-api/create-photo-version-2! database
                                                      [(albums-api/make-image-version (in-mem/make-mem-fs {:store mock-fs})
                                                                                      #uuid "f3c84f81-4c9f-42c0-9e68-c4aeedf7cae4"
                                                                                      "andrewslai.com"
                                                                                      "png"
                                                                                      (util/now)
                                                                                      "thumbnail")]))))

    (testing "Can retrieve the version from the DB"
      (is (match? [{:path           (re-pattern (format "media/%s/thumbnail.png" UUID-REGEX))
                    :photo-id       #uuid "f3c84f81-4c9f-42c0-9e68-c4aeedf7cae4"
                    :hostname       "andrewslai.com"
                    :filename       "thumbnail.png"}]
                  (albums-api/get-full-photos database {:id #uuid "f3c84f81-4c9f-42c0-9e68-c4aeedf7cae4"}))))

    (testing "Can retrieve the version from the DB with string"
      (is (match? [{:path           (re-pattern (format "media/%s/thumbnail.png" UUID-REGEX))
                    :photo-id       #uuid "f3c84f81-4c9f-42c0-9e68-c4aeedf7cae4"
                    :hostname       "andrewslai.com"
                    :filename       "thumbnail.png"}]
                  (albums-api/get-full-photos database {:id "f3c84f81-4c9f-42c0-9e68-c4aeedf7cae4"}))))))


(deftest create-photo-version-postgres--test
  (testing "Postgres specific conversion"
    (let [database (embedded-postgres/fresh-db!)
          mock-fs (atom {})]
      (albums-api/create-photo-version-2! database
                                          [(albums-api/make-image-version (in-mem/make-mem-fs {:store mock-fs})
                                                                          #uuid "f3c84f81-4c9f-42c0-9e68-c4aeedf7cae4"
                                                                          "andrewslai.com"
                                                                          "png"
                                                                          (util/now)
                                                                          "thumbnail")])

      (testing "Can retrieve the version from the DB"
        (is (match? [{:path           (re-pattern (format "media/%s/thumbnail.png" UUID-REGEX))
                      :photo-id       #uuid "f3c84f81-4c9f-42c0-9e68-c4aeedf7cae4"
                      :hostname       "andrewslai.com"
                      :filename       "thumbnail.png"}]
                    (albums-api/get-full-photos database {:id #uuid "f3c84f81-4c9f-42c0-9e68-c4aeedf7cae4"}))))

      (testing "Can retrieve the version from the DB with string"
        (is (match? [{:path           (re-pattern (format "media/%s/thumbnail.png" UUID-REGEX))
                      :photo-id       #uuid "f3c84f81-4c9f-42c0-9e68-c4aeedf7cae4"
                      :hostname       "andrewslai.com"
                      :filename       "thumbnail.png"}]
                    (albums-api/get-full-photos database {:id "f3c84f81-4c9f-42c0-9e68-c4aeedf7cae4"})))))))


(deftest new-image-test
  (let [database (embedded-h2/fresh-db!)
        mock-fs (atom {})
        captured-notification (atom [])]
    (testing "Create photo version"
      (is (match? {:photo-id uuid?
                   :versions vector?}
                  (albums-api/new-image {:database               database
                                         :static-content-adapter (in-mem/make-mem-fs {:store mock-fs})
                                         :notify-image-resizer!  (fn [& inputs] (swap! captured-notification conj (apply hash-map inputs)))}
                                        "andrewslai.com"
                                        {:filename          "myfile.png"
                                         :photo-id          #uuid "11111111-4c9f-42c0-9e68-c4aeedf7cae4"
                                         :more-metadata     12345
                                         :extension         "png"
                                         :tempfile          (io/file (io/resource "public/images/lock.svg"))
                                         :file-input-stream (u/->file-input-stream (io/file (io/resource "public/images/lock.svg")))}))))

    (testing "Can retrieve the version from the DB"
      (is (match? [{:path           (re-pattern (format "media/%s/raw.png" UUID-REGEX))
                    :photo-id       #uuid "11111111-4c9f-42c0-9e68-c4aeedf7cae4"
                    :hostname       "andrewslai.com"
                    :filename       "raw.png"}
                   {:filename "thumbnail.png"}
                   {:filename "gallery.png"}
                   {:filename "monitor.png"}
                   {:filename "mobile.png"}]
                  (albums-api/get-full-photos database {:id #uuid "11111111-4c9f-42c0-9e68-c4aeedf7cae4"}))))

    (testing "Can retrieve the version from the DB with string"
      (is (match? [{:path           (re-pattern (format "media/%s/raw.png" UUID-REGEX))
                    :photo-id       #uuid "11111111-4c9f-42c0-9e68-c4aeedf7cae4"
                    :hostname       "andrewslai.com"
                    :filename       "raw.png"}
                   {:filename "thumbnail.png"}
                   {:filename "gallery.png"}
                   {:filename "monitor.png"}
                   {:filename "mobile.png"}]
                  (albums-api/get-full-photos database {:id "11111111-4c9f-42c0-9e68-c4aeedf7cae4"}))))

    (testing "Sends notifications"
      ;; The resize notify message is the raw's write-location URL (s3://bucket/key),
      ;; derived once and reused for the write. The in-memory store reports its
      ;; :storage-root ("media") as the bucket; a real S3 store reports the host
      ;; bucket. No message-attributes — the resizer reads only the URL body.
      (is (= [{:message "s3://media/media/11111111-4c9f-42c0-9e68-c4aeedf7cae4/raw.png"
               :subject "image-resize-requested"}]
             @captured-notification)))
    (testing "File exists in Filesystem"
      (is (match? {"media"
                   {"11111111-4c9f-42c0-9e68-c4aeedf7cae4"
                    {"raw.png"
                     {:path     (re-pattern (format "media/%s/raw.png" UUID-REGEX))
                      :name     "raw.png"
                      :content  tu/file-input-stream?
                      :metadata {:filename      "myfile.png"
                                 :more-metadata 12345}
                      }}}}
                  @mock-fs)))))

(deftest get-full-photos-scoped-handle-confines-by-tenant-test
  ;; The photo GET handlers used to thread :hostname into the query map by
  ;; hand (and the file-serving handler omitted it entirely). Scoping the db
  ;; handle confines get-full-photos to one tenant even when the query has no
  ;; :hostname key — full_photos is a VIEW, so this also confirms injection
  ;; works through the join, not just base tables.
  (let [db (embedded-h2/fresh-db!)
        _  (albums-api/create-photo! db {:id (u/uuid) :photo-title "shared" :hostname "andrewslai.com"})
        _  (albums-api/create-photo! db {:id (u/uuid) :photo-title "shared" :hostname "caheriaguilar.com"})]
    (testing "a raw handle sees both tenants' photos"
      (is (= 2 (count (albums-api/get-full-photos db {:photo-title "shared"})))))
    (testing "a scoped handle sees only its tenant's photo, query omitting :hostname"
      (is (match? [{:hostname "andrewslai.com"}]
                  (albums-api/get-full-photos (tenant/scope db "andrewslai.com") {:photo-title "shared"})))
      (is (empty? (albums-api/get-full-photos (tenant/scope db "nobody.com") {:photo-title "shared"}))))))
