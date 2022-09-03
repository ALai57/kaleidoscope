(ns andrewslai.clj.entities.album-test
  (:require [andrewslai.clj.persistence.rdbms :as rdbms]
            [andrewslai.clj.entities.album :as album]
            [andrewslai.clj.entities.photo :as photo]
            [andrewslai.clj.persistence.rdmbs.embedded-h2-impl :as rdmbs.embedded-h2-impl]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log])
  (:import [java.util UUID]))

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
      (f))))

(def example-album
  {:album-name     "Test album"
   :description    "My description"
   :cover-photo-id #uuid "d947c6b0-679f-4067-9747-3d282833a27d"
   :created-at     #inst "2021-05-27T18:30:39.000Z"
   :modified-at    #inst "2021-05-27T18:30:39.000Z"})

(deftest create-and-retrieve-album-test
  (let [database (embedded-h2/fresh-db!)]
    (testing "3 example-albums seeded in DB"
      ;; Migrations seed db now for convenience
      (is (= 3 (count (album/get-all-albums database)))))

    (let [{:keys [id]} (album/create-album! database example-album)]
      (testing "Insert the example-album"
        (is (uuid? id)))

      (testing "Can retrieve example-album from the DB"
        (is (match? example-album (album/get-album database (:album-name example-album))))
        (is (match? example-album (album/get-album-by-id database id))))

      (testing "Can update an album"
        (album/update-album! database {:id         id
                                       :album-name "Something new"})
        (is (match? {:album-name "Something new"}
                    (album/get-album-by-id database id)))))))

(deftest album-contents-test
  (let [database (embedded-h2/fresh-db!)]
    ;; 3 example-albums seeded in DB
    (let [album-id  (:id (first (album/get-all-albums database)))
          photo-ids (map :id (photo/get-all-photos database))]

      (testing "No contents in the album to start"
        (is (= [] (album/get-album-contents database album-id))))

      (testing "Content gets added to the album"
        (let [album-content (album/add-photos-to-album! database album-id photo-ids)]
          (is (= 3 (count album-content)))
          (is (vector? album-content))
          (is (= 3 (count (album/get-album-contents database album-id))))

          (testing "Content is deleted"
            (album/remove-content-from-album! database album-id (map :id album-content))
            (is (= 0 (count (album/get-album-contents database album-id))))))))))

(deftest get-all-album-contents-test
  (let [database (embedded-h2/fresh-db!)]
    ;; 3 example-albums seeded in DB
    (let [album-ids (map :id (album/get-all-albums database))
          photo-ids (map :id (photo/get-all-photos database))]

      (testing "No contents in the album to start"
        (is (= [] (album/get-all-contents database))))

      (testing "Content gets added to different albums"
        (let [album-content (mapv (partial album/add-photos-to-album! database)
                                  album-ids
                                  photo-ids)]
          (is (= 3 (count album-content)))))

      (testing "Contents come from different albums"
        (is (= 3 (count (set (map :album-name (album/get-all-contents database)))))))
      )))
