(ns kaleidoscope.http-api.ping-test
  (:require [clojure.test :refer [deftest is]]
            [kaleidoscope.http-api.ping :as ping]
            [matcher-combinators.test :refer [match?]]
            [ring.mock.request :as mock]))

(deftest determine-sha-test
  (is (match? {:revision string?}
              (ping/get-version-details))))

(deftest ping-routes-test
  (is (match? {:status 200
               :body   {:revision string?}}
              (ping/ping-routes (mock/request :get "/ping")))))


(comment
  ;; This test assumes that git is installed on the machine
  (deftest short-sha-test
    (is (re-matches #"^[a-z0-9]{7}$" (ping/short-sha))))
  )