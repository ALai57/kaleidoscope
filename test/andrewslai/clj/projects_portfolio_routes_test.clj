(ns andrewslai.clj.projects-portfolio-routes-test
  (:require [andrewslai.clj.persistence.postgres-test :as ptest]
            [andrewslai.clj.entities.portfolio :as portfolio]
            [andrewslai.clj.test-utils :as tu :refer [defdbtest]]
            [andrewslai.clj.utils :refer [parse-body]]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [is testing]]))

(defdbtest resume-info-test ptest/db-spec
  (testing "GET endpoint returns portfolio"
    (let [response (tu/get-request "/projects-portfolio")]
      (is (= 200 (:status response)))
      (is (s/valid? :andrewslai.portfolio/portfolio (parse-body response))))))
