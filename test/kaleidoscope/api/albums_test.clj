(ns kaleidoscope.api.albums-test
  (:require [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.api.albums :as albums-api]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.persistence.filesystem.in-memory-impl :as in-mem]
            [kaleidoscope.utils.core :as u]
            [kaleidoscope.test-main :as tm]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]
            [clojure.java.io :as io]
            [kaleidoscope.test-utils :as tu])
  (:import [java.util UUID]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level tm/*test-log-level*
      (f))))

(def example-album
  {:album-name     "Test album"
   :description    "My description"
   :cover-photo-id #uuid "d947c6b0-679f-4067-9747-3d282833a27d"})

(def example-photo
  {:id #uuid "88c0f460-01c7-4051-a549-f7f123f6acc2"})

(def example-photo-version
  {:photo-id       #uuid "f3c84f81-4c9f-42c0-9e68-c4aeedf7cae4" ;; From db-seed
   :path           "some/path"
   :storage-driver "local"
   :storage-root   "resources/"
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

      (testing "Can update an album"
        (albums-api/update-album! database {:id         id
                                            :album-name "Something new"})
        (is (match? [{:album-name "Something new"}]
                    (albums-api/get-albums database {:id id})))))))

(deftest album-contents-test
  ;; 3 example-albums seeded in DB
  (let [database  (embedded-h2/fresh-db!)
        album-id  (:id (first (albums-api/get-albums database)))
        photo-ids (map :id (albums-api/get-photos database))]

    (testing "No contents in the album to start"
      (is (= [] (albums-api/get-album-contents database {:album-id album-id}))))

    (testing "Content gets added to the album"
      (let [album-content (albums-api/add-photos-to-album! database album-id photo-ids)]
        (is (= 3 (count album-content)))
        (is (vector? album-content))
        (is (= 3 (count (albums-api/get-album-contents database {:album-id album-id}))))

        (testing "Content is deleted"
          (albums-api/remove-content-album-link! database (map :id album-content))
          (is (= 0 (count (albums-api/get-album-contents database {:album-id album-id})))))))))

(deftest get-album-contents-test
  ;; 3 example-albums seeded in DB
  (let [database  (embedded-h2/fresh-db!)
        album-ids (map :id (albums-api/get-albums database))
        photo-ids (map :id (albums-api/get-photos database))]

    (testing "No contents in the album to start"
      (is (= [] (albums-api/get-album-contents database))))

    (testing "Content gets added to different albums"
      (let [album-content (mapv (partial albums-api/add-photos-to-album! database)
                                album-ids
                                photo-ids)]
        (is (= 3 (count album-content)))))

    (testing "Contents come from different albums"
      (is (= 3 (count (set (map :album-name (albums-api/get-album-contents database)))))))
    ))

(deftest create-and-retrieve-photo-test
  (let [database (embedded-h2/fresh-db!)]
    (testing "example-photos were seeded into the DB"
      ;; Migrations seed db now for convenience
      (is (= 3 (count (albums-api/get-photos database)))))

    (testing "Insert the example-photo"
      (is (match? {:id uuid?}
                  (albums-api/create-photo! database example-photo))))

    (testing "Can retrieve example-photo from the DB"
      (is (match? [example-photo] (albums-api/get-photos database {:id #uuid "88c0f460-01c7-4051-a549-f7f123f6acc2"}))))))

(deftest create-and-retrieve-photo-version-test
  (let [database (embedded-h2/fresh-db!)]
    (testing "example-photos were seeded into the DB"
      ;; Migrations seed db now for convenience
      (is (= 3 (count (albums-api/get-photo-versions database)))))

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
           [{:id   #uuid "4a3db5ec-358c-4e36-9f19-3e0193001ff4"
             :path "media/processed/1d675bdc-e6ec-4522-8920-4950d33d4eee-500.jpg"}
            {:id   #uuid "4a3db5ec-358c-4e36-9f19-3e0193001ff4"
             :path "media/processed/20210422_134816 (2)-500.jpg"}
            {:id   #uuid "4a3db5ec-358c-4e36-9f19-3e0193001ff4"
             :path "media/processed/20210422_134824 (2)-500.jpg"}]
           (albums-api/-get-full-photos database {:id #uuid "4a3db5ec-358c-4e36-9f19-3e0193001ff4"}))))))

(def UUID-REGEX
  "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

(deftest create-photo-version--test
  (let [database (embedded-h2/fresh-db!)
        mock-fs  (atom {})]
    (testing "Create photo version"
      (is (match? [{:id uuid?}]
                  (albums-api/create-photo-version-2! database
                                                      (in-mem/make-mem-fs {:store mock-fs})
                                                      (assoc example-photo-version
                                                             :file {:filename          "myfile.png"
                                                                    :more-metadata     12345
                                                                    :file-input-stream (u/->file-input-stream (io/file (io/resource "public/images/lock.svg")))})))))

    (testing "Can retrieve the version from the DB"
      (is (match? [{:path           (re-pattern (format "media/%s/thumbnail.png" UUID-REGEX))
                    :photo-id       #uuid "f3c84f81-4c9f-42c0-9e68-c4aeedf7cae4"
                    :hostname       "andrewslai.localhost"
                    :filename       "thumbnail.png"
                    :storage-driver "in-memory"
                    :storage-root   "media"}]
                  (albums-api/-get-full-photos database {:id #uuid "f3c84f81-4c9f-42c0-9e68-c4aeedf7cae4"}))))


    (testing "File exists in Filesystem"
      (is (match? {"media"
                   {"f3c84f81-4c9f-42c0-9e68-c4aeedf7cae4"
                    {"thumbnail.png"
                     {:path     (re-pattern (format "media/%s/thumbnail.png" UUID-REGEX))
                      :name     "thumbnail.png"
                      :content  tu/file-input-stream?
                      :metadata {:filename      "myfile.png"
                                 :more-metadata 12345}
                      }}}}
                  @mock-fs)))))
