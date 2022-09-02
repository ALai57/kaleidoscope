(ns andrewslai.clj.http-api.middleware-test
  (:require [andrewslai.clj.http-api.auth.buddy-backends :as bb]
            [andrewslai.clj.http-api.middleware :as sut]
            [andrewslai.clj.test-utils :as tu]
            [cheshire.core :as json]
            [clojure.string :as string]
            [clojure.test :refer :all]
            [matcher-combinators.test]
            [ring.mock.request :as mock]))

(deftest standard-stack-test
  (let [captured-request (atom nil)
        app              (sut/standard-stack (fn [req]
                                               (reset! captured-request req)
                                               {:status 200
                                                :body   {:foo "bar"}}))]
    (is (match? {:status  200
                 :headers {"Content-Type" #"application/json"}
                 :body    (json/generate-string {:foo "bar"})}
                (app (mock/request :get "/"))))
    (is (match? {:uri        "/index.html"
                 :request-id string?}
                @captured-request))))

(deftest auth-stack-happy-path-test
  (let [captured-request (atom nil)
        mw-stack         (sut/auth-stack {:auth         (bb/authenticated-backend {:realm_access {:roles ["myrole"]}})
                                          :access-rules (tu/restricted-access "myrole")})
        app              (mw-stack (fn [req]
                                     (reset! captured-request req)
                                     {:status 200
                                      :body   {:foo "bar"}}))]
    (is (match? {:status 200
                 :body   {:foo "bar"}}
                (app (-> (mock/request :get "/")
                         (mock/header "Authorization" "Bearer x")))))
    (is (match? {:identity {:realm_access {:roles ["myrole"]}}}
                @captured-request))))

(deftest auth-stack-wrong-role-test
  (let [captured-request (atom nil)
        mw-stack         (sut/auth-stack {:auth         (bb/authenticated-backend {:realm_access {:roles ["myrole"]}})
                                          :access-rules (tu/restricted-access "wrongrole")})
        app              (mw-stack (fn [req]
                                     (reset! captured-request req)
                                     {:status 200
                                      :body   {:foo "bar"}}))]
    (is (match? {:status 401}
                (app (-> (mock/request :get "/")
                         (mock/header "Authorization" "Bearer x")))))
    (is (nil? (:identity @captured-request)))))
