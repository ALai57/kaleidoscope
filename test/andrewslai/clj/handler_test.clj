(ns andrewslai.clj.handler-test
  (:require [andrewslai.clj.handler :as h]
            [andrewslai.clj.static-content :as sc]
            [andrewslai.clj.test-utils :as tu]
            [buddy.auth.middleware :refer [wrap-authentication]]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [matcher-combinators.test]
            [ring.mock.request :as mock]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
      (f))))

(deftest ping-test
  (let [handler (tu/wrap-clojure-response (h/andrewslai-app {}))]
    (is (match? {:status  200
                 :headers {"Content-Type" #"application/json"}
                 :body    {:revision string?}}
                (handler (mock/request :get "/ping"))))))

(deftest home-test
  (let [handler (h/andrewslai-app {:static-content (sc/classpath-static-content-wrapper "public" {})})]
    (is (match? {:status  200
                 :headers {"Content-Type" #"text/html"}
                 :body    tu/file?}
                (handler (mock/request :get "/"))))))

(deftest swagger-test
  (let [handler (tu/wrap-clojure-response (h/andrewslai-app {}))]
    (is (match? {:status  200
                 :headers {"Content-Type" #"application/json"}
                 :body    map?}
                (handler (mock/request :get "/swagger.json"))))))

(deftest logging-test
  (let [logging-atom (atom [])
        handler      (h/andrewslai-app {:logging (tu/captured-logging logging-atom)})]
    (handler (mock/request :get "/ping"))
    (is (= 1 (count @logging-atom)))))

(deftest authentication-middleware-test
  (let [handler (wrap-authentication identity (tu/authenticated-backend))]
    (is (match? {:identity {:foo "bar"}}
                (handler {:headers {"Authorization" (tu/bearer-token {:foo :bar})}})))))

(deftest authentication-middleware-failure-test
  (let [handler (wrap-authentication identity (tu/unauthenticated-backend))]
    (is (not (-> {:headers {"Authorization" (str "Bearer " tu/valid-token)}}
                 (handler)
                 (:identity))))))
