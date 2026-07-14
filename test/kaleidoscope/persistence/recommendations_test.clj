(ns kaleidoscope.persistence.recommendations-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.persistence.interests :as interests-persistence]
            [kaleidoscope.persistence.recommendations :as recommendations-persistence]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level :error
      (f))))

(def candidates
  [{:kind "article" :title "Trusted piece" :source "PBS Frontline"
    :url "https://example.com/1" :est-time "18 min"
    :why "Directly on your stated intent." :origin "trusted"}
   {:kind "podcast" :title "Novel find" :source "The Gradient"
    :url "https://example.com/2" :est-time "40 min"
    :why "New source covering your keywords." :origin "novel"}])

(deftest recommendations-crud-test
  (let [db          (embedded-h2/fresh-db!)
        interest    (interests-persistence/create-interest!
                     db {:user-id "reader@example.com" :intent "Tech journalism" :taste-profile {}})
        interest-id (:id interest)]

    (testing "empty candidate list inserts nothing and returns []"
      (is (= [] (recommendations-persistence/create-recommendations! db interest-id []))))

    (testing "bulk create shelves candidates with status shelved"
      ;; sorted alphabetically by :title: "Novel find" < "Trusted piece"
      (is (match? [{:status "shelved" :origin "novel"} {:status "shelved" :origin "trusted"}]
                  (sort-by :title (recommendations-persistence/create-recommendations!
                                   db interest-id candidates)))))

    (testing "get-recommendations filters by status and kind"
      (is (= 2 (count (recommendations-persistence/get-recommendations db interest-id {}))))
      (is (= 2 (count (recommendations-persistence/get-recommendations db interest-id {:status "shelved"}))))
      (is (match? [{:kind "podcast"}]
                  (recommendations-persistence/get-recommendations db interest-id {:kind "podcast"}))))

    (testing "update-recommendation-status! is scoped to the interest"
      (let [rec-id (:id (first (recommendations-persistence/get-recommendations db interest-id {})))]
        (is (nil? (recommendations-persistence/update-recommendation-status!
                   db rec-id (random-uuid) "queued")))
        (is (match? {:status "queued"}
                    (recommendations-persistence/update-recommendation-status!
                     db rec-id interest-id "queued")))))

    (testing "archive-shelved! archives the remaining shelved rows but not queued ones"
      (recommendations-persistence/archive-shelved! db interest-id)
      (is (= 1 (count (recommendations-persistence/get-recommendations db interest-id {:status "archived"}))))
      (is (= 1 (count (recommendations-persistence/get-recommendations db interest-id {:status "queued"}))))
      (is (= 0 (count (recommendations-persistence/get-recommendations db interest-id {:status "shelved"})))))))
