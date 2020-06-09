(ns andrewslai.clj.projects-portfolio-routes-test
  (:require [andrewslai.clj.persistence.postgres-test :as ptest]
            [andrewslai.clj.persistence.projects-portfolio :as portfolio]
            [andrewslai.clj.persistence.postgres :as postgres]
            [andrewslai.clj.test-utils :refer [defdbtest]]
            [andrewslai.clj.utils :refer [parse-body]]
            [andrewslai.clj.handler :as h]
            [ring.mock.request :as mock]
            [clojure.test :refer [is testing]]))

(def session-atom (atom {}))

(defn components []
  {:portfolio (-> ptest/db-spec
                  postgres/->Postgres
                  portfolio/->ProjectPortfolioDatabase)})

(defn test-app []
  (h/wrap-middleware h/bare-app (components)))

(defn- get-request [route]
  (->> route
       (mock/request :get)
       ((test-app))))

(defdbtest get-resume-info-test  ptest/db-spec
  (testing "get-resume-info endpoint returns a resume-info data structure"
    (let [response (get-request "/get-resume-info")]
      (is (= 200 (:status response)))
      (is (= #{:organizations, :projects, :skills}
             (set (keys (parse-body response))))))))
