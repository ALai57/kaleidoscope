(ns andrewslai.clj.admin-routes-test
  (:require [andrewslai.clj.auth.keycloak :as keycloak]
            [andrewslai.clj.embedded-postgres :refer [with-embedded-postgres]]
            [andrewslai.clj.routes.andrewslai :as andrewslai]
            [andrewslai.clj.test-utils :as tu]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [matcher-combinators.test]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
      (f))))

(deftest authenticated-user-test
  (are [description auth-backend expected]
    (testing description
      (is (match? expected
                  (tu/app-request (andrewslai/andrewslai-app {:auth auth-backend})
                                  {:request-method :get
                                   :uri            "/admin/"
                                   :headers        (tu/auth-header [])}))))

    "Authenticated user can access admin route"
    (tu/authenticated-backend)
    {:status 200 :body {:message "Got to the admin-route!"}}

    "Unauthenticated user cannot access admin route"
    (tu/unauthenticated-backend)
    {:status 401 :body {:reason "Not authorized"}}
    ))
