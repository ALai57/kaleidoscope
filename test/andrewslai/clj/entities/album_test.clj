(ns andrewslai.clj.entities.album-test
  (:require [andrewslai.clj.persistence.postgres :as pg]
            [andrewslai.clj.entities.album :as album]
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
  {:album-name     "My first album"
   :description    "My description"
   :cover-photo-id #uuid "d947c6b0-679f-4067-9747-3d282833a27d"
   :created-at     #inst "2021-05-27T18:30:39.000Z"
   :modified-at    #inst "2021-05-27T18:30:39.000Z"})

(deftest create-and-retrieve-album-test
  (let [database (pg/->NextDatabase (embedded-h2/fresh-db!))]
    (testing "example-photo doesn't exist in the database"
      (is (= [] (album/get-all-albums database))))

    (let [{:keys [id]} (album/create-album! database example-album)]
      (testing "Insert the example-article"
        (is (match? {:id uuid?} (album/create-album! database example-album))))

      (testing "Can retrieve example-article from the DB"
        (is (match? example-album (album/get-album database (:album-name example-album))))
        (is (match? example-album (album/get-album-by-id database id))))

      (testing "Can Update an album"
        (album/update-album! database {:id         id
                                       :album-name "Something new"})
        (is (match? {:album-name "Something new"}
                    (album/get-album-by-id database id)))))))
