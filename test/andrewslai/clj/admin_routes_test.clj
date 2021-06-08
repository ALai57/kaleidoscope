(ns andrewslai.clj.admin-routes-test
  (:require [andrewslai.clj.auth.keycloak :as keycloak]
            [andrewslai.clj.embedded-postgres :refer [with-embedded-postgres]]
            [andrewslai.clj.handler :as h]
            [andrewslai.clj.test-utils :as tu]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [matcher-combinators.test]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
      (f))))

(deftest authorized-user-test
  (are [description auth-backend expected]
    (testing description
      (is (match? expected
                  (tu/app-request (h/andrewslai-app {:auth auth-backend})
                                  {:request-method :get
                                   :uri            "/admin/"
                                   :headers        {"Authorization" (str "Bearer " tu/valid-token)}}))))

    "Authorized user can access admin route"
    (tu/authorized-backend)
    {:status 200 :body {:message "Got to the admin-route!"}}

    "Unauthorized user cannot access admin route"
    (tu/unauthorized-backend)
    {:status 401 :body {:reason "Not authorized"}}
    ))
