(ns kaleidoscope.http-api.ping-test
  (:require [clojure.test :refer :all]
            [kaleidoscope.http-api.ping :as ping]
            [matcher-combinators.test :refer [match?]]
            [ring.mock.request :as mock]))

(deftest ping-routes-test
  (is (match? {:status 200
               :body   {:revision string?}}
              (ping/ping-routes (mock/request :get "/ping")))))


(comment
  (require '[kaleidoscope.utils.versioning :as v])

  ;; This test assumes that git is installed on the machine
  (deftest short-sha-test
    (is (re-matches #"^[a-z0-9]{7}$" (v/short-sha))))
  )
