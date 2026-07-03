(ns kaleidoscope.api.themes-test
  (:require [kaleidoscope.api.themes :as themes]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.persistence.rdbms.embedded-postgres-impl :as embedded-postgres]
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
                    (themes/get-themes database (dissoc example-theme :config)))))

      (testing "Ownership predicate"
        (is (themes/owns? database "user-1" theme-id))
        (is (not (themes/owns? database "user-2" theme-id))))

      (testing "Non-owner cannot update the theme"
        (is (nil? (themes/update-theme! database "user-2" "andrewslai.com"
                                        {:display-name "hijacked"
                                         :id           theme-id
                                         :config       {:secondary {:something "else"}}})))
        (is (match? [{:display-name "mytheme"}]
                    (themes/get-themes database (dissoc example-theme :config)))))

      (testing "Owner cannot update the theme via the wrong site — same nil as not-found"
        (is (nil? (themes/update-theme! database "user-1" "sahiltalkingcents.com"
                                        {:display-name "hijacked"
                                         :id           theme-id
                                         :config       {:secondary {:something "else"}}})))
        (is (match? [{:display-name "mytheme"}]
                    (themes/get-themes database (dissoc example-theme :config)))))

      (testing "Can update the theme"
        (is (match? [{:display-name "another-theme"
                      :id           theme-id}]
                    (themes/update-theme! database "user-1" "andrewslai.com"
                                          {:display-name "another-theme"
                                           :id           theme-id
                                           :config       {:secondary {:something "else"}}}))))

      (testing "Non-owner cannot delete the theme"
        (is (nil? (themes/delete-theme! database "not-the-owner" "andrewslai.com" theme-id))))

      (testing "Owner cannot delete the theme via the wrong site"
        (is (nil? (themes/delete-theme! database "user-1" "sahiltalkingcents.com" theme-id))))

      (testing "Group owner can delete the theme"
        (is (true? (themes/delete-theme! database "user-1" "andrewslai.com" theme-id)))
        (is (empty? (#'themes/get-themes database example-theme)))))))

(deftest postgres-compatibility-test
  (testing "JSON B works"
    (let [database (embedded-postgres/fresh-db!)]
      (try
        (testing "example-theme doesn't exist in the database"
          (is (empty? (themes/get-themes database (dissoc example-theme :config)))))

        (let [[{theme-id :id} :as result] (themes/create-theme! database example-theme)]
          (testing "Insert the example-theme"
            (is (not-empty result)))

          (testing "Can retrieve example-theme from the DB"
            (is (match? [example-theme]
                        (themes/get-themes database example-theme))))

          (testing "Ownership predicate"
            (is (themes/owns? database "user-1" theme-id))
            (is (not (themes/owns? database "user-2" theme-id))))

          (testing "Non-owner cannot update the theme"
            (is (nil? (themes/update-theme! database "user-2" "andrewslai.com"
                                            {:display-name "hijacked"
                                             :id           theme-id
                                             :config       {:secondary {:something "else"}}})))
            (is (match? [{:display-name "mytheme"}]
                        (themes/get-themes database (dissoc example-theme :config)))))

          (testing "Owner cannot update the theme via the wrong site"
            (is (nil? (themes/update-theme! database "user-1" "sahiltalkingcents.com"
                                            {:display-name "hijacked"
                                             :id           theme-id
                                             :config       {:secondary {:something "else"}}})))
            (is (match? [{:display-name "mytheme"}]
                        (themes/get-themes database (dissoc example-theme :config)))))

          (testing "Can update the theme"
            (is (match? [{:display-name "another-theme"
                          :id           theme-id}]
                        (themes/update-theme! database "user-1" "andrewslai.com"
                                              {:display-name "another-theme"
                                               :id           theme-id
                                               :config       {:secondary {:something "else"}}}))))

          (testing "Non-owner cannot delete the theme"
            (is (nil? (themes/delete-theme! database "not-the-owner" "andrewslai.com" theme-id))))

          (testing "Group owner can delete the theme"
            (themes/delete-theme! database "user-1" "andrewslai.com" theme-id)
            (is (empty? (#'themes/get-themes database (dissoc example-theme :config))))))
        (finally
          (.close database))))))
