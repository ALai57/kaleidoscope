(ns andrewslai.clj.api.albums-test
  (:require [andrewslai.clj.persistence.rdbms :as rdbms]
            [andrewslai.clj.api.albums :as albums-api]
            [andrewslai.clj.api.photos :as photos-api]
            [andrewslai.clj.persistence.rdbms.embedded-h2-impl :as embedded-h2]
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
      (is (= 3 (count (albums-api/get-albums database)))))

    (let [[{:keys [id]}] (albums-api/create-album! database example-album)]
      (testing "Insert the example-album"
        (is (uuid? id)))

      (testing "Can retrieve example-album from the DB"
        (is (match? [example-album] (albums-api/get-albums database {:album-name (:album-name example-album)})))
        (is (match? [example-album] (albums-api/get-albums database {:id id}))))

      (testing "Can update an album"
        (albums-api/update-album! database {:id         id
                                            :album-name "Something new"})
        (is (match? [{:album-name "Something new"}]
                    (albums-api/get-albums database {:id id})))))))

(deftest album-contents-test
  (let [database (embedded-h2/fresh-db!)]
    ;; 3 example-albums seeded in DB
    (let [album-id  (:id (first (albums-api/get-albums database)))
          photo-ids (map :id (photos-api/get-all-photos database))]

      (testing "No contents in the album to start"
        (is (= [] (albums-api/get-album-contents database {:album-id album-id}))))

      (testing "Content gets added to the album"
        (let [album-content (albums-api/add-photos-to-album! database album-id photo-ids)]
          (is (= 3 (count album-content)))
          (is (vector? album-content))
          (is (= 3 (count (albums-api/get-album-contents database {:album-id album-id}))))

          (testing "Content is deleted"
            (albums-api/remove-content-album-link! database (map :id album-content))
            (is (= 0 (count (albums-api/get-album-contents database {:album-id album-id}))))))))))

(deftest get-album-contents-test
  (let [database (embedded-h2/fresh-db!)]
    ;; 3 example-albums seeded in DB
    (let [album-ids (map :id (albums-api/get-albums database))
          photo-ids (map :id (photos-api/get-all-photos database))]

      (testing "No contents in the album to start"
        (is (= [] (albums-api/get-album-contents database))))

      (testing "Content gets added to different albums"
        (let [album-content (mapv (partial albums-api/add-photos-to-album! database)
                                  album-ids
                                  photo-ids)]
          (is (= 3 (count album-content)))))

      (testing "Contents come from different albums"
        (is (= 3 (count (set (map :album-name (albums-api/get-album-contents database)))))))
      )))
