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
    (let [{:keys [status]}
          ((h/wrap-middleware h/bare-app {}) (mock/request :get "/"))]
      (is (= 200 status)))))

;; TODO: Add tests that logging works properly

(defn captured-logging [logging-atom]
  {:level :debug
   :appenders {:println {:enabled? true,
                         :level :debug
                         :output-fn
                         (fn [data] (force (:msg_ data)))
                         :fn (fn [data]
                               (let [{:keys [output_]} data]
                                 (swap! logging-atom conj (force output_))))}}})

(deftest logging-test
  (testing "Logging works properly"
    (let [logging-atom (atom [])
          {:keys [status body]}
          ((h/wrap-middleware h/bare-app
                              {:logging (captured-logging logging-atom)})
           (mock/request :get "/ping"))]
      (is (= 1 (count @logging-atom))))))

#_(logging-test)

#_(let [logging-atom (atom [])]
    (timbre/with-config (captured-logging logging-atom)
      (timbre/info "Yo"))
    @logging-atom)

#_(println (:appenders timbre/*config*))

