(ns andrewslai.clj.handler-test
  (:require [andrewslai.clj.handler :as h]
            [andrewslai.clj.utils :refer [parse-body]]
            [andrewslai.clj.test-utils :refer [get-request]]
            [clojure.test :refer [deftest is testing]]
            [clojure.spec.alpha :as s]
            [ring.mock.request :as mock]
            [taoensso.timbre :as timbre]))

(deftest ping-test
  (testing "Ping works properly"
    (let [response (get-request "/ping")]
      (is (= 200 (:status response)))
      (is (= #{:sha :service-status} (-> response
                                         parse-body
                                         keys
                                         set))))))

(deftest home-test
  (testing "Index page works properly"
    (let [{:keys [status]} (get-request "/")]
      (is (= 200 status)))))

(defn captured-logging [logging-atom]
  {:level :debug
   :appenders {:println {:enabled? true,
                         :level :debug
                         :output-fn (fn [data] (force (:msg_ data)))
                         :fn (fn [data]
                               (let [{:keys [output_]} data]
                                 (swap! logging-atom conj (force output_))))}}})

(deftest logging-test
  (testing "Logging works properly"
    (let [logging-atom (atom [])
          app (h/wrap-middleware h/app-routes
                                 {:logging (captured-logging logging-atom)})]
      (get-request "/ping" app)
      (is (= 1 (count @logging-atom))))))

(deftest swagger-test
  (let [app (h/wrap-middleware h/app-routes {})]
    (get-request "/swagger.json" app)))
