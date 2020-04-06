(ns andrewslai.clj.handler-test
  (:require [andrewslai.clj.handler :as h]
            [andrewslai.clj.utils :refer [body->map]]
            [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [taoensso.timbre :as timbre]))

(deftest ping-test
  (testing "Ping works properly"
    (let [{:keys [status body]}
          ((h/wrap-middleware h/bare-app {})
           (mock/request :get "/ping"))]
      (is (= 200 status))
      (is (= #{:sha :service-status} (-> body
                                         body->map
                                         keys
                                         set))))))

(deftest home-test
  (testing "Index page works properly"
    (let [{:keys [status]} ((h/wrap-middleware h/bare-app {}) (mock/request :get "/"))]
      (is (= 200 status)))))

;; TODO: Add tests that logging works properly
(deftest logging-test
  (testing "Logging works properly"
    (let [{:keys [status body]}
          ((h/wrap-middleware h/bare-app {:logging timbre/*config*})
           (mock/request :get "/ping"))]
      (is (= 200 status))
      (is (= #{:sha :service-status} (-> body
                                         body->map
                                         keys
                                         set))))))

;;(logging-test)
