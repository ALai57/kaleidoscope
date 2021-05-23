(ns andrewslai.clj.admin-routes-test
  (:require [andrewslai.clj.auth.keycloak :as keycloak]
            [andrewslai.clj.embedded-postgres :refer [with-embedded-postgres]]
            [andrewslai.clj.test-utils :as tu]
            [clojure.test :refer [deftest is use-fixtures]]
            [matcher-combinators.test]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
      (f))))

(defn admin-route
  [components options]
  (tu/http-request :get "/admin/" components options))

(deftest authorized-user-test
  (is (match? {:status 200 :body {:message "Got to the admin-route!"}}
              (admin-route {:auth (tu/authorized-backend)}
                           {:headers {"Authorization" (str "Bearer " tu/valid-token)}}))))

(deftest unauthorized-user-test
  (is (match? {:status 401 :body {:reason "Not authorized"}}
              (admin-route {:auth (tu/unauthorized-backend)}
                           {:headers {"Authorization" (str "Bearer " tu/valid-token)}}))))
