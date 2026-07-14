(ns kaleidoscope.http-api.interests-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.http-api.interests :refer [reitit-interests-routes]]
            [kaleidoscope.http-api.kaleidoscope :as kal]
            [kaleidoscope.http-api.middleware :as mw]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.test-utils :as tu]
            [kaleidoscope.workflows.mock :as workflow-mock]
            [matcher-combinators.test :refer [match?]]
            [reitit.ring :as ring]
            [ring.mock.request :as mock]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level :error
      (f))))

;; wrap-clojure-response parses JSON bodies back to keywordized maps so the
;; tests below can assert on body content, not just status.
(defn- test-app
  [components]
  (let [config (update-in mw/reitit-configuration
                          [:data :middleware]
                          (fn [middleware] (concat middleware [(kal/inject-components components)])))]
    (tu/wrap-clojure-response
     (ring/ring-handler
      (ring/router [reitit-interests-routes] config)))))

(def user-id "reader@example.com")

(defn- as-user
  [request]
  (assoc request :identity {:user-id user-id}))

;; Coercion-failure and rate-limit paths — both run in middleware ahead of the
;; handler, so none of these trigger a Claude call.
(deftest interest-validation-test
  (let [app (test-app {:database (embedded-h2/fresh-db!)})]
    (mw/reset-rate-limits!)
    (testing "Missing intent is rejected"
      (is (match? {:status 400}
                  (app (-> (mock/request :post "/interests")
                           (mock/json-body {}))))))
    (testing "Oversized intent is rejected before it can inflate a prompt"
      (is (match? {:status 400}
                  (app (-> (mock/request :post "/interests")
                           (mock/json-body {:intent (apply str (repeat 5001 "a"))}))))))
    (testing "A novelty ratio outside 0.0-1.0 is rejected — the dial is a user control with hard bounds"
      (is (match? {:status 400}
                  (app (-> (mock/request :post "/interests")
                           (mock/json-body {:intent        "ok"
                                            :taste-profile {:novelty-ratio 1.5}}))))))
    (testing "An unrecognized format is rejected"
      (is (match? {:status 400}
                  (app (-> (mock/request :post "/interests")
                           (mock/json-body {:intent        "ok"
                                            :taste-profile {:formats ["doomscroll"]}}))))))
    (testing "A malformed interest-id path segment is rejected by coercion"
      (is (match? {:status 400}
                  (app (mock/request :get "/interests/not-a-uuid")))))))

(deftest curate-rate-limit-test
  (let [app     (test-app {:database (embedded-h2/fresh-db!)})
        request #(-> (mock/request :post (str "/interests/" (random-uuid) "/curate"))
                     (mock/json-body {}))]
    (mw/reset-rate-limits!)
    (dotimes [_ 5]
      (app (request)))
    (testing "6th curate request from the same IP within the window is rate limited"
      (is (match? {:status 429}
                  (app (request)))))))

(deftest interest-crud-http-test
  (let [db  (embedded-h2/fresh-db!)
        app (test-app {:database db :workflow-executor (workflow-mock/make-mock-executor)})]
    (mw/reset-rate-limits!)
    (let [created (app (-> (mock/request :post "/interests")
                           (mock/json-body {:intent "Modern jazz history"})
                           as-user))]
      (testing "POST /interests creates an interest with the default taste profile"
        (is (match? {:status 200
                     :body   {:intent        "Modern jazz history"
                              :taste-profile {:novelty-ratio 0.5}}}
                    created)))
      (let [interest-id (get-in created [:body :id])]
        (testing "GET /interests lists it; GET by id returns it; both scoped to the identity"
          (is (match? {:status 200 :body [{:intent "Modern jazz history"}]}
                      (app (as-user (mock/request :get "/interests")))))
          (is (match? {:status 200 :body {:intent "Modern jazz history"}}
                      (app (as-user (mock/request :get (str "/interests/" interest-id))))))
          (is (match? {:status 404}
                      (app (-> (mock/request :get (str "/interests/" interest-id))
                               (assoc :identity {:user-id "attacker@example.com"}))))))
        (testing "PUT /interests/:id merges a taste-profile edit"
          (is (match? {:status 200 :body {:taste-profile {:novelty-ratio 0.8 :cadence "weekly"}}}
                      (app (-> (mock/request :put (str "/interests/" interest-id))
                               (mock/json-body {:taste-profile {:novelty-ratio 0.8}})
                               as-user)))))
        (testing "DELETE /interests/:id removes it"
          (is (match? {:status 204}
                      (app (as-user (mock/request :delete (str "/interests/" interest-id))))))
          (is (match? {:status 404}
                      (app (as-user (mock/request :get (str "/interests/" interest-id)))))))))))
