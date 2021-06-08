(ns andrewslai.clj.handler-test
  (:require [andrewslai.clj.handler :as h]
            [andrewslai.clj.static-content :as sc]
            [andrewslai.clj.test-utils :as tu]
            [buddy.auth.middleware :refer [wrap-authentication]]
            [clojure.test :refer [deftest is use-fixtures]]
            [matcher-combinators.test]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
      (f))))

(deftest ping-test
  (is (match? {:status  200
               :headers {"Content-Type" #"application/json"}
               :body    {:revision string?}}
              (tu/app-request (h/andrewslai-app {})
                              {:request-method :get
                               :uri            "/ping"}))))

(deftest home-test
  (is (match? {:status  200
               :headers {"Content-Type" #"text/html"}
               :body    tu/file?}
              (tu/app-request (h/andrewslai-app {:static-content (sc/classpath-static-content-wrapper "public" {})})
                              {:request-method :get
                               :uri            "/"}
                              {:parser identity}))))

(deftest swagger-test
  (is (match? {:status  200
               :headers {"Content-Type" #"application/json"}
               :body    map?}
              (tu/app-request (h/andrewslai-app {})
                              {:request-method :get
                               :uri            "/swagger.json"}))))

(deftest logging-test
  (let [logging-atom (atom [])]
    (tu/app-request (h/andrewslai-app {:logging (tu/captured-logging logging-atom)})
                    {:request-method :get
                     :uri            "/ping"})
    (is (= 1 (count @logging-atom)))))

(deftest authentication-middleware-test
  (let [app (wrap-authentication identity (tu/authorized-backend))]
    (is (match? {:identity {:foo "bar"}}
                (app {:headers {"Authorization" (tu/bearer-token {:foo :bar})}})))))

(deftest authentication-middleware-failure-test
  (let [app  (wrap-authentication identity (tu/unauthorized-backend))
        resp (app {:headers {"Authorization" (str "Bearer " tu/valid-token)}})]
    (is (nil? (:identity resp)))))

