(ns andrewslai.clj.handler-test
  (:require [andrewslai.clj.handler :as h]
            [andrewslai.clj.utils :refer [parse-body]]
            [andrewslai.clj.test-utils :refer [get-request]]
            [clojure.test :refer [deftest is testing]]
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

(deftest extract-swagger-specs-test
  (let [swagger {:paths [["/" {:get
                               {:components
                                {:schemas
                                 {"user"
                                  {:spec :andrewslai.user/user-profile}}}}}]]}]
    (is (= {"user" {:spec :andrewslai.user/user-profile}}
           (h/extract-specs swagger)))))

(deftest swagger-specs->components-test
  (is (= {"user" {:type "string"
                  :x-allOf [{:type "string"} {} {}]
                  :title "andrewslai.user/username"}}
         (h/specs->components {"user" {:spec :andrewslai.user/username}}))))

(deftest swagger-test
  (let [app (h/wrap-middleware h/app-routes {})]
    (get-request "/swagger.json" app)))
