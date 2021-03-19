(ns andrewslai.clj.routes.ping-test
  (:require [andrewslai.clj.routes.ping :refer [get-version-details ping-handler]]
            [compojure.api.sweet :refer [api]]
            [clojure.test :refer [deftest testing is]]
            [matcher-combinators.test]))

(deftest sha-parsing
  (testing "Short SHA parsing"
    (is (string? (:revision (get-version-details))))))

(deftest ping-routes-test
  (testing "Ping"
    (is (match? {:revision string?}
                (:body (ping-handler))))))
