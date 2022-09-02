(ns andrewslai.clj.http-api.auth.buddy-backends-test
  (:require [andrewslai.clj.http-api.auth.buddy-backends :as bb]
            [buddy.auth.backends.token :as token]
            [buddy.auth.middleware :as bmw]
            [buddy.auth.protocols :as proto]
            [clj-http.client :as http]
            [clojure.test :refer :all]
            [matcher-combinators.test :refer [match?]]
            [ring.mock.request :as mock]))

(deftest backend-parses-test
  (let [backend (bb/bearer-token-backend identity)]
    (testing "Bearer token can be parsed"
      (is (= "x" (proto/-parse backend
                               (-> (mock/request :get "/")
                                   (mock/header "Authorization" "Bearer x"))))))

    (testing "Other Authorization tokens cannot be parsed"
      (is (nil? (proto/-parse backend
                              (-> (mock/request :get "/")
                                  (mock/header "Authorization" "Token x"))))))))

(deftest backend-authentication-test
  (let [backend (bb/bearer-token-backend (constantly "Hello"))]
    (testing "Bearer token can be parsed"
      (let [request (-> (mock/request :get "/")
                        (mock/header "Authorization" "Bearer x"))]
        (is (match? {:identity "Hello"}
                    (bmw/authentication-request request backend)))))
    (testing "Bearer token must be present to authenticate"
      (let [request (-> (mock/request :get "/")
                        (mock/header "Authorization" "Bearer"))]
        (is (= request
               (bmw/authentication-request request backend)))))))

(deftest unauthenticated-backend-test
  (let [backend bb/unauthenticated-backend
        request (-> (mock/request :get "/")
                    (mock/header "Authorization" "Bearer x"))]
    (testing "Bearer token can be parsed"
      (is (= request
             (bmw/authentication-request request backend))))))

(deftest authenticated-backend-test
  (let [backend (bb/authenticated-backend {:foo "bar"})
        request (-> (mock/request :get "/")
                    (mock/header "Authorization" "Bearer x"))]
    (testing "Bearer token can be parsed"
      (is (match? {:identity {:foo "bar"}}
                  (bmw/authentication-request request backend))))))
