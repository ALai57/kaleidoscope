(ns andrewslai.clj.projects-portfolio-routes-test
  (:require [andrewslai.clj.embedded-postgres :refer [with-embedded-postgres]]
            [andrewslai.clj.handler :as h]
            [andrewslai.clj.test-utils :as tu]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is]]
            [matcher-combinators.test]))


(defn portfolio?
  [x]
  (s/valid? :andrewslai.portfolio/portfolio x))

(deftest resume-info-test
  (with-embedded-postgres database
    (is (match? {:status 200
                 :body portfolio?}
                (tu/app-request (h/andrewslai-app {:database database})
                                {:request-method :get
                                 :uri            "/projects-portfolio"})))))
