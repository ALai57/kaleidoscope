(ns andrewslai.clj.routes.ping-test
  (:require [andrewslai.clj.routes.ping :refer [get-sha ping-handler]]
            [compojure.api.sweet :refer [api]]
            [clojure.test :refer [deftest testing is]]))

(deftest sha-parsing
  (testing "Short SHA parsing"
    (is (= 7 (count (get-sha))))))

(deftest ping-routes-test
  (testing "Ping"
    (is (= #{:service-status :sha} (-> (ping-handler)
                                       :body
                                       keys
                                       set)))))


