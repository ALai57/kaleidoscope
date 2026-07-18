(ns kaleidoscope.api.portfolio-test
  (:require [clojure.test :refer :all]
            [kaleidoscope.api.portfolio :as portfolio]
            [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.persistence.tenant :as tenant]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.test-main :as tm]
            [kaleidoscope.test-utils :as tu]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level tm/*test-log-level*
      (tu/with-schema-enforcement 'kaleidoscope.api.portfolio
        (f)))))

(deftest get-portfolio-test
  (let [db (embedded-h2/fresh-db!)]
    (is (portfolio/get-portfolio db))))

(deftest portfolio-is-site-scoped-test
  ;; portfolio_entries/_links had no tenancy and were served publicly and
  ;; globally. They are now hostname-scoped like the rest of the CMS.
  (let [db (embedded-h2/fresh-db!)]
    (rdbms/insert! db :portfolio-entries {:name        "caheri-node"
                                          :type        "skill"
                                          :url         ""
                                          :image-url   ""
                                          :description ""
                                          :tags        ""
                                          :hostname    "caheriaguilar.com"})
    (testing "a scoped handle sees only its own site's portfolio nodes"
      (let [{:keys [nodes]} (portfolio/get-portfolio (tenant/scope db "andrewslai.com"))]
        (is (seq nodes))
        (is (every? #(= "andrewslai.com" (:hostname %)) nodes))
        (is (not-any? #(= "caheri-node" (:name %)) nodes))))
    (testing "the other site sees only its own"
      (let [{:keys [nodes]} (portfolio/get-portfolio (tenant/scope db "caheriaguilar.com"))]
        (is (= ["caheri-node"] (map :name nodes)))))))
