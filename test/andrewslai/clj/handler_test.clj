(ns andrewslai.clj.handler-test
  (:require [andrewslai.clj.handler :as h]
            [andrewslai.clj.test-utils :as u]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [clojure.spec.alpha :as s]
            [matcher-combinators.test]
            [ring.mock.request :as mock]
            [taoensso.timbre :as timbre]))


(deftest ping-test
  (is (match? {:status 200
               :headers {"Content-Type" string?}
               :body {:service-status "ok"
                      :sha string?}}
              (u/http-request :get "/ping" {}))))

(deftest home-test
  (is (match? {:status 200
               :headers {"Content-Type" string?}
               :body seq?}
              (u/http-request :get "/" {} {:parser u/->hiccup}))))

(deftest swagger-test
  (is (match? {:status 200 :body map?}
              (u/http-request :get "/swagger.json" {}))))

(deftest logging-test
  (let [logging-atom (atom [])]
    (u/http-request :get "/ping" {:logging (u/captured-logging logging-atom)})
    (is (= 1 (count @logging-atom)))))
