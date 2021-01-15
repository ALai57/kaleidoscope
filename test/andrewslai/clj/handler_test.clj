(ns andrewslai.clj.handler-test
  (:require [andrewslai.clj.handler :as h]
            [andrewslai.clj.utils :refer [parse-body]]
            [andrewslai.clj.test-utils :refer [get-request]]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [clojure.spec.alpha :as s]
            [matcher-combinators.test]
            [ring.mock.request :as mock]
            [taoensso.timbre :as timbre]))


(defn json-request
  [method endpoint components & payload]
  (let [app (h/wrap-middleware h/app-routes components)]
    (update (app {:request-method method :uri endpoint})
            :body #(json/parse-string (slurp %) keyword))))

(deftest ping-test
  (is (match? {:status 200
               :headers {"Content-Type" string?}
               :body {:service-status "ok"
                      :sha string?}}
              (json-request :get "/ping" {}))))

(deftest home-test
  (let [{:keys [status]} (get-request "/")]
    (is (= 200 status))))

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
