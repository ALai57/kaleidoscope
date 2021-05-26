(ns andrewslai.clj.handler-test
  (:require [andrewslai.clj.auth.keycloak :as keycloak]
            [andrewslai.clj.handler :as h]
            [andrewslai.clj.test-utils :as tu]
            [buddy.auth.middleware :refer [wrap-authentication]]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [clojure.test :refer [deftest is use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.properties :as prop]
            [compojure.api.sweet :refer [api defroutes GET routes]]
            [matcher-combinators.test]
            [ring.util.codec :as codec]
            [ring.util.request :as req]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
      (f))))

(deftest ping-test
  (is (match? {:status 200
               :headers {"Content-Type" string?}
               :body {:revision string?}}
              (tu/http-request :get "/ping" {}))))

(deftest home-test
  (is (match? {:status  200
               :headers {"Content-Type" string?}
               :body    tu/file?}
              (tu/http-request :get "/" {} {:parser identity}))))

(deftest swagger-test
  (is (match? {:status 200 :body map?}
              (tu/http-request :get "/swagger.json" {}))))

(deftest logging-test
  (let [logging-atom (atom [])]
    (tu/http-request :get "/ping" {:logging (tu/captured-logging logging-atom)})
    (is (= 1 (count @logging-atom)))))

(deftest authentication-middleware-test
  (let [app (wrap-authentication identity (tu/authorized-backend))]
    (is (match? {:identity {:sub  "1234567890"
                            :name "John Doe"
                            :iat  1516239022}}
                (app {:headers {"Authorization" (str "Bearer " tu/valid-token)}})))))

(deftest authentication-middleware-failure-test
  (let [app  (wrap-authentication identity (tu/unauthorized-backend))
        resp (app {:headers {"Authorization" (str "Bearer " tu/valid-token)}})]
    (is (nil? (:identity resp)))))

