(ns andrewslai.clj.user-routes-test
  (:require [andrewslai.clj.auth.keycloak :as keycloak]
            [andrewslai.clj.embedded-postgres :refer [with-embedded-postgres]]
            [andrewslai.clj.test-utils :as tu]
            [clojure.test :refer [deftest is]]
            [matcher-combinators.test]))

(defn admin-route
  [components options]
  (tu/http-request :get "/admin/" components options))

(deftest authorized-login-test
  (is (match? {:status 200 :body {:message "Got to the admin-route!"}}
              (admin-route {:auth (keycloak/keycloak-backend (tu/authorized-backend))}
                           {:headers {"Authorization" (str "Bearer " tu/valid-token)}}))))

(deftest unauthorized-login-test
  (is (match? {:status 401 :body {:reason "Not authorized"}}
              (admin-route {:auth (keycloak/keycloak-backend (tu/unauthorized-backend))}
                           {:headers {"Authorization" (str "Bearer " tu/valid-token)}}))))
