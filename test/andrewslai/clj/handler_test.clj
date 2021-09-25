(ns andrewslai.clj.handler-test
  (:require [andrewslai.clj.handler :as h]
            [andrewslai.clj.static-content :as sc]
            [andrewslai.clj.test-utils :as tu]
            [buddy.auth.middleware :refer [wrap-authentication]]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [matcher-combinators.test]
            [ring.mock.request :as mock]
            [taoensso.timbre :as log]))

;; wedding routes
(deftest authentication-middleware-test
  (let [handler (wrap-authentication identity (tu/authenticated-backend))]
    (is (match? {:identity {:foo "bar"}}
                (handler {:headers {"Authorization" (tu/bearer-token {:foo :bar})}})))))

(deftest authentication-middleware-failure-test
  (let [handler (wrap-authentication identity (tu/unauthenticated-backend))]
    (is (not (-> {:headers {"Authorization" (str "Bearer " tu/valid-token)}}
                 (handler)
                 (:identity))))))
