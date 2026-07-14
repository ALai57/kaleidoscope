(ns kaleidoscope.persistence.interests-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.persistence.projects :as projects-persistence]
            [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.utils.core :as utils]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level :error
      (f))))

(deftest interests-tables-exist-test
  (let [db      (embedded-h2/fresh-db!)
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
