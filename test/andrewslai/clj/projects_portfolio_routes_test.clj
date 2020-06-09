(ns andrewslai.clj.projects-portfolio-routes-test
  (:require [andrewslai.clj.persistence.postgres-test :as ptest]
            [andrewslai.clj.persistence.projects-portfolio :as portfolio]
            [andrewslai.clj.persistence.postgres :as postgres]
            [andrewslai.clj.test-utils :refer [defdbtest] :as tu]
            [andrewslai.clj.utils :refer [parse-body]]
            [andrewslai.clj.handler :as h]
            [ring.mock.request :as mock]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [is testing]]))

(defdbtest resume-info-test  ptest/db-spec
  (testing "GET endpoint returns project-portfolio"
    (let [response (tu/get-request "/get-resume-info")]
      (is (= 200 (:status response)))
      (is (s/valid? ::portfolio/project-portfolio (parse-body response))))))
