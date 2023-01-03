(ns andrewslai.clj.http-api.middleware-test
  (:require [amazonica.aws.s3 :as s3]
            [andrewslai.clj.http-api.auth.buddy-backends :as bb]
            [andrewslai.clj.http-api.middleware :as sut]
            [andrewslai.clj.persistence.filesystem.s3-impl :as s3-storage]
            [andrewslai.clj.test-utils :as tu]
            [biiwide.sandboxica.alpha :as sandbox]
            [cheshire.core :as json]
            [clojure.test :refer :all]
            [ring.mock.request :as mock]
            [ring.util.response :as response]
            [taoensso.timbre :as log]
            [matcher-combinators.test :refer [match?]]))

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
      (f))))

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
    (is (match? {:uri        "/"
                 :request-id string?}
                @captured-request))))

(deftest auth-stack-happy-path-test
  (let [captured-request (atom nil)
        mw-stack         (sut/auth-stack (bb/authenticated-backend {:realm_access {:roles ["myrole"]}})
                                         (tu/restricted-access "myrole"))
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
        mw-stack         (sut/auth-stack (bb/authenticated-backend {:realm_access {:roles ["myrole"]}})
                                         (tu/restricted-access "wrongrole"))
        app              (mw-stack (fn [req]
                                     (reset! captured-request req)
                                     {:status 200
                                      :body   {:foo "bar"}}))]
    (is (match? {:status 401}
                (app (-> (mock/request :get "/")
                         (mock/header "Authorization" "Bearer x")))))
    (is (nil? (:identity @captured-request)))))
