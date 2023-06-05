(ns kaleidoscope.api.audiences-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.api.audiences :as audiences-api]
            [kaleidoscope.api.groups :as groups]
            [kaleidoscope.api.groups-test :as groups-test]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.test-main :as tm]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]))

(use-fixtures :each
  (fn [f]
    (log/with-min-level tm/*test-log-level*
      (f))))

;;;; setup fixtures and run test
(deftest create-and-retrieve-audience-test
  (let [database         (embedded-h2/fresh-db!)
        [{group-id :id}] (groups/create-group! database groups-test/example-group)]
    (testing "0 example-audiences seeded in DB"
      (is (= 0 (count (audiences-api/get-article-audiences database)))))

    (testing "Fail to add audience if hostname does not match article hostname"
      (is (nil? (audiences-api/add-audience-to-article! database
                                                        {:id       1
                                                         :hostname "does-not-match"}
                                                        {:id group-id})))
      (is (empty? (audiences-api/get-article-audiences database {}))))

    ;; Use article ID 1 below because it is seeded in the DB with hostname
    ;; `andrewslai.localhost`
    (let [[{:keys [id]}] (audiences-api/add-audience-to-article! database
                                                                 {:id       1
                                                                  :hostname "andrewslai.localhost"}
                                                                 {:id group-id})]
      (testing "Add the example-audience"
        (is (uuid? id)))

      (testing "Can retrieve example-audience from the DB"
        (is (match? [{:id       id
                      :hostname "andrewslai.localhost"}] (audiences-api/get-article-audiences database (select-keys example-audience [:audience-name]))))
        (is (match? [{:id id}] (audiences-api/get-article-audiences database {:id id}))))
      (audiences-api/get-article-audiences database {:id id})

      (testing "Can delete a audience"
        (audiences-api/delete-article-audience! database id)

        (is (empty? (audiences-api/get-article-audiences database {:id id})))))))
