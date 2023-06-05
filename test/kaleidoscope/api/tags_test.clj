(ns kaleidoscope.api.tags-test
  (:require [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.api.tags :as tags-api]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.persistence.rdbms.embedded-postgres-impl :as embedded-postgres]
            [kaleidoscope.persistence.filesystem.in-memory-impl :as in-mem]
            [kaleidoscope.utils.core :as u]
            [kaleidoscope.test-main :as tm]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]
            [clojure.java.io :as io]
            [kaleidoscope.test-utils :as tu])
  (:import [java.util UUID]))


(use-fixtures :each
  (fn [f]
    (log/with-min-level tm/*test-log-level*
      (f))))

(def example-tag
  {:id       #uuid "88c0f460-01c7-4051-a549-f7f123f6acc2"
   :name     "my-example-tag"
   :hostname "andrewslai.com"})

(deftest create-and-retrieve-tag-test
  (let [database (embedded-h2/fresh-db!)]
    (testing "0 example-tags seeded in DB"
      (is (= 0 (count (tags-api/get-tags database)))))

    (let [[{:keys [id]}] (tags-api/create-tag! database example-tag)]
      (testing "Insert the example-tag"
        (is (uuid? id)))

      (testing "Can retrieve example-tag from the DB"
        (is (match? [example-tag] (tags-api/get-tags database (select-keys example-tag [:tag-name]))))
        (is (match? [example-tag] (tags-api/get-tags database {:id id}))))
      (tags-api/get-tags database (select-keys example-tag [:tag-name]))

      (testing "Can update an tag"
        (tags-api/update-tag! database {:id   id
                                        :name "Something new"})
        (is (match? [{:name "Something new"}]
                    (tags-api/get-tags database {:id id})))))))
