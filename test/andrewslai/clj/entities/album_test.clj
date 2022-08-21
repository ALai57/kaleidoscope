(ns andrewslai.clj.entities.album-test
  (:require [andrewslai.clj.persistence.postgres :as pg]
            [andrewslai.clj.entities.album :as album]
            [andrewslai.clj.entities.photo :as photo]
            [andrewslai.clj.persistence.embedded-h2 :as embedded-h2]
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
  (let [database (pg/->NextDatabase (embedded-h2/fresh-db!))]
    (testing "3 example-albums seeded in DB"
      ;; Migrations seed db now for convenience
      (is (= 3 (count (album/get-all-albums database)))))

    (let [{:keys [id]} (album/create-album! database example-album)]
      (testing "Insert the example-album"
        (is (match? {:id uuid?} (album/create-album! database example-album))))

      (testing "Can retrieve example-album from the DB"
        (is (match? example-album (album/get-album database (:album-name example-album))))
        (is (match? example-album (album/get-album-by-id database id))))

      (testing "Can update an album"
        (album/update-album! database {:id         id
                                       :album-name "Something new"})
        (is (match? {:album-name "Something new"}
                    (album/get-album-by-id database id)))))))

(deftest album-contents-test
  (let [database (pg/->NextDatabase (embedded-h2/fresh-db!))]
    ;; 3 example-albums seeded in DB
    (let [album-id      (:id (first (album/get-all-albums database)))
          photo-id      (:id (first (photo/get-all-photos database)))]

      (testing "No contents in the album to start"
        (is (= [] (album/get-album-contents database album-id))))

      (testing "Content gets added to the album"
        (let [album-content (album/add-photo-to-album! database album-id photo-id)]
          (is (match? {:album-content-id  (:id album-content)
                       :album-id          album-id
                       :photo-id          photo-id
                       :added-to-album-at inst?
                       :photo-src         string?
                       :photo-title       string?
                       :album-name        string?
                       :cover-photo-id    uuid?}
                      (album/get-album-content database album-id (:id album-content))))
          (is (= 1 (count (album/get-album-contents database album-id)))))))))
