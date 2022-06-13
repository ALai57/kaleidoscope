(ns andrewslai.clj.entities.photos-test
  (:require [andrewslai.clj.persistence.postgres :as pg]
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

(def example-photo
  {:id          #uuid "88c0f460-01c7-4051-a549-f7f123f6acc2"
   :photo-name  "example/photo"
   :created-at  #inst "2021-05-27T18:30:39.000Z"
   :modified-at #inst "2021-05-27T18:30:39.000Z"})

(deftest create-and-retrieve-photo-test
  (let [database (pg/->NextDatabase (embedded-h2/fresh-db!))]
    (testing "example-photo doesn't exist in the database"
      (is (= [] (photo/get-all-photos database))))

    (testing "Insert the example-article"
      (is (match? [{:id uuid?}] (photo/create-photo! database example-photo))))

    (testing "Can retrieve example-article from the DB"
      (is (match? example-photo (photo/get-photo database "example/photo"))))))
