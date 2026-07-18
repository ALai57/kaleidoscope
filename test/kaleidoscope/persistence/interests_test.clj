(ns kaleidoscope.persistence.interests-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.persistence.interests :as interests-persistence]
            [kaleidoscope.persistence.projects :as projects-persistence]
            [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.persistence.tenant :as tenant]
            [kaleidoscope.utils.core :as utils]
            [matcher-combinators.test :refer [match?]]
            [next.jdbc :as next]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level :error
      (f))))

(deftest interests-tables-exist-test
  (let [db      (tenant/scope (embedded-h2/fresh-db!) "andrewslai.com")
        user-id "reader@example.com"
        project (projects-persistence/create-project! db {:user-id user-id
                                                          :title   "Interest: tech journalism"})
        now     (utils/now)]
    (testing "interests accepts a row and round-trips the JSONB taste profile"
      (is (match? {:user-id       user-id
                   :intent        "Investigative journalism about technology"
                   :taste-profile {:novelty-ratio 0.5 :trusted-sources ["PBS Frontline"]}}
                  (first (rdbms/insert! db :interests
                                        {:id            (utils/uuid)
                                         :user-id       user-id
                                         :project-id    (:id project)
                                         :intent        "Investigative journalism about technology"
                                         :taste-profile {:novelty-ratio   0.5
                                                         :trusted-sources ["PBS Frontline"]}
                                         :created-at    now
                                         :updated-at    now})))))
    (testing "recommendations accepts a row and defaults origin/status"
      (let [interest-id (:id (first (rdbms/find-by-keys db :interests {:user-id user-id})))]
        (is (match? {:interest-id interest-id
                     :origin      "novel"
                     :status      "shelved"}
                    (first (rdbms/insert! db :recommendations
                                          {:id          (utils/uuid)
                                           :interest-id interest-id
                                           :kind        "article"
                                           :title       "The Age of Surveillance"
                                           :source      "Quanta Magazine"
                                           :url         "https://example.com/a"
                                           :est-time    "18 min"
                                           :why         "Directly on your stated intent."
                                           :added-at    now}))))))))

(deftest interest-crud-test
  (let [db       (tenant/scope (embedded-h2/fresh-db!) "andrewslai.com")
        user-id  "reader@example.com"
        interest (interests-persistence/create-interest!
                  db {:user-id       user-id
                      :intent        "Long-form journalism about technology and power"
                      :taste-profile {:trusted-sources ["PBS Frontline" "The Hill"]
                                      :novelty-ratio   0.5}})]
    (testing "create-interest! returns the interest with a backing project"
      (is (match? {:user-id       user-id
                   :intent        "Long-form journalism about technology and power"
                   :taste-profile {:novelty-ratio 0.5}}
                  interest))
      (is (uuid? (:project-id interest)))
      (is (some? (projects-persistence/get-project db (:project-id interest) user-id))))

    (testing "get-interest is scoped to the owner"
      (is (match? {:id (:id interest)} (interests-persistence/get-interest db (:id interest) user-id)))
      (is (nil? (interests-persistence/get-interest db (:id interest) "attacker@example.com"))))

    (testing "get-interests lists only the user's interests"
      (is (= 1 (count (interests-persistence/get-interests db user-id))))
      (is (empty? (interests-persistence/get-interests db "attacker@example.com"))))

    (testing "get-interest-by-project-id resolves the backing project"
      (is (match? {:id (:id interest)}
                  (interests-persistence/get-interest-by-project-id db (:project-id interest)))))

    (testing "update-interest! merges nothing implicitly — it sets what it is given, scoped to owner"
      (is (nil? (interests-persistence/update-interest! db (:id interest) "attacker@example.com"
                                                        {:intent "hijacked"})))
      (is (match? {:intent        "Refined intent"
                   :taste-profile {:novelty-ratio 1.0}}
                  (interests-persistence/update-interest! db (:id interest) user-id
                                                          {:intent        "Refined intent"
                                                           :taste-profile {:novelty-ratio 1.0}}))))

    (testing "delete-interest! is scoped and tears down the backing project"
      (is (nil? (interests-persistence/delete-interest! db (:id interest) "attacker@example.com")))
      (is (some? (interests-persistence/delete-interest! db (:id interest) user-id)))
      (is (nil? (interests-persistence/get-interest db (:id interest) user-id)))
      (is (nil? (projects-persistence/get-project db (:project-id interest) user-id))))))

(deftest get-projects-excludes-interest-backing-projects-test
  (let [db       (tenant/scope (embedded-h2/fresh-db!) "andrewslai.com")
        user-id  "reader@example.com"
        project  (projects-persistence/create-project! db {:user-id user-id :title "A normal project"})
        interest (interests-persistence/create-interest!
                  db {:user-id       user-id
                      :intent        "Long-form journalism about technology and power"
                      :taste-profile {:trusted-sources ["PBS Frontline"] :novelty-ratio 0.5}})]
    (testing "get-projects lists the normal project but not the interest's backing project"
      (let [listed (projects-persistence/get-projects db user-id)]
        (is (= [(:id project)] (mapv :id listed)))))

    (testing "get-project can still fetch the backing project by id directly"
      (is (some? (projects-persistence/get-project db (:project-id interest) user-id))))))
