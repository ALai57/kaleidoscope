(ns andrewslai.clj.handler-test
  (:require [andrewslai.clj.handler :as h]
            [clojure.test :refer [deftest testing is]]
            [ring.mock.request :as mock]
            [andrewslai.clj.utils :refer [parse-response-body]]))

(deftest ping-test
  (testing "Ping works properly"
    (is (= #{:sha :service-status} (-> (mock/request :get "/ping")
                                       h/app
                                       parse-response-body
                                       keys
                                       set)))))
(deftest home-test
  (testing "Index page works properly")
  (is (= 200 (-> (mock/request :get "/")
                 h/app
                 :status))))

