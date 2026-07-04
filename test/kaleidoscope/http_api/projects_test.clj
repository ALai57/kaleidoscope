(ns kaleidoscope.http-api.projects-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.http-api.kaleidoscope :as kal]
            [kaleidoscope.http-api.middleware :as mw]
            [kaleidoscope.http-api.projects :refer [reitit-projects-routes]]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [matcher-combinators.test :refer [match?]]
            [reitit.ring :as ring]
            [ring.mock.request :as mock]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level :error
      (f))))

(defn- test-app
  [components]
  (let [config (update-in mw/reitit-configuration
                          [:data :middleware]
                          (fn [middleware] (concat middleware [(kal/inject-components components)])))]
    (ring/ring-handler
     (ring/router [reitit-projects-routes] config))))

(deftest project-id-path-coercion-test
  (let [app (test-app {:database (embedded-h2/fresh-db!)})]
    (testing "A well-formed UUID path segment is coerced and reaches the handler"
      (is (match? {:status 404}
                  (app (mock/request :get (str "/projects/" (random-uuid)))))))

    (testing "A malformed UUID path segment is rejected by coercion before the handler runs"
      (is (match? {:status 400}
                  (app (mock/request :get "/projects/not-a-uuid")))))))

;; These test the coercion-failure and rate-limit paths only - both are
;; enforced by middleware that runs before the handler (and before any
;; :identity/DB lookup), so none of these requests trigger a Claude call.
(deftest create-project-validation-test
  (let [app (test-app {:database (embedded-h2/fresh-db!)})]
    (mw/reset-rate-limits!)
    (testing "Missing title is rejected"
      (is (match? {:status 400}
                  (app (-> (mock/request :post "/projects")
                           (mock/json-body {:description "no title"}))))))
    (testing "Oversized description is rejected before it can inflate a prompt"
      (is (match? {:status 400}
                  (app (-> (mock/request :post "/projects")
                           (mock/json-body {:title       "ok"
                                            :description (apply str (repeat 20001 "a"))}))))))))

(deftest create-project-rate-limit-test
  (let [app     (test-app {:database (embedded-h2/fresh-db!)})
        ;; No title, so coercion always rejects with 400 before ever
        ;; reaching the DB/scorer - the rate limiter runs ahead of
        ;; coercion, so this still exercises it without a live Claude call.
        request #(mock/request :post "/projects")]
    (mw/reset-rate-limits!)
    (dotimes [_ 5]
      (app (request)))
    (testing "6th request from the same IP within the window is rate limited"
      (is (match? {:status 429}
                  (app (request)))))))

(deftest score-project-validation-test
  (let [app (test-app {:database (embedded-h2/fresh-db!)})]
    (mw/reset-rate-limits!)
    (testing "More than 20 definition-ids is rejected before fanning out into 20+ Claude calls"
      (is (match? {:status 400}
                  (app (-> (mock/request :post (str "/projects/" (random-uuid) "/scores"))
                           (mock/json-body {:definition-ids (vec (repeatedly 21 random-uuid))}))))))))

(deftest score-project-rate-limit-test
  (let [app        (test-app {:database (embedded-h2/fresh-db!)})
        project-id (random-uuid)
        ;; 21 ids always fails coercion (400) before reaching the DB/scorer -
        ;; the rate limiter still counts the attempt.
        request    #(-> (mock/request :post (str "/projects/" project-id "/scores"))
                        (mock/json-body {:definition-ids (vec (repeatedly 21 random-uuid))}))]
    (mw/reset-rate-limits!)
    (dotimes [_ 5]
      (app (request)))
    (testing "6th request from the same IP within the window is rate limited"
      (is (match? {:status 429}
                  (app (request)))))))
