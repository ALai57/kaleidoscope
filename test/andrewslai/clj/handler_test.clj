(ns andrewslai.clj.handler-test
  (:require [andrewslai.clj.test-utils :as u]
            [clojure.test :refer [deftest is]]
            [matcher-combinators.test]))

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
