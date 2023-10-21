(ns kaleidoscope.api.themes-test
  (:require [kaleidoscope.api.themes :as themes]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [cheshire.core :as json]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    ;; Manually override the minimum error level because some of the tests will
    ;; emit warnings
    (log/with-min-level :error
      (f))))

(def example-theme
  {:display-name "mytheme"
   :owner-id     "user-1"
   :hostname     "andrewslai.com"
   :config       {:primary {:main "#AAA111"}}})

(deftest create-and-retrieve-theme-test
  (let [database (embedded-h2/fresh-db!)]
    (testing "example-theme doesn't exist in the database"
      (is (empty? (themes/get-themes database example-theme))))

    (let [[{theme-id :id} :as result] (themes/create-theme! database example-theme)]
      (testing "Insert the example-theme"
        (is (not-empty result)))

      (testing "Can retrieve example-theme from the DB"
        (is (match? [example-theme]
                    (themes/get-themes database example-theme))))

      (testing "Ownership predicate"
        (is (themes/owns? database "user-1" theme-id))
        (is (not (themes/owns? database "user-2" theme-id))))

      (testing "Non-owner cannot delete the theme"
        (is (nil? (themes/delete-theme! database "not-the-owner" theme-id))))

      (testing "Group owner can delete the theme"
        (is (= [] (themes/delete-theme! database "user-1" theme-id)))
        (is (empty? (#'themes/get-themes database example-theme)))))))
