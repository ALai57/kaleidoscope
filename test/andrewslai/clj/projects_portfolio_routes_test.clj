(ns andrewslai.clj.projects-portfolio-routes-test
  (:require [andrewslai.clj.embedded-postgres :refer [with-embedded-postgres]]
            [andrewslai.clj.test-utils :as tu]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is]]
            [matcher-combinators.test]))

(deftest resume-info-test
  (with-embedded-postgres database
    (is (match? {:status 200
                 :body #(s/valid? :andrewslai.portfolio/portfolio %)}
                (tu/http-request :get "/projects-portfolio" {:database database})))))
