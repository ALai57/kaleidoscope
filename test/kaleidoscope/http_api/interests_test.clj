(ns kaleidoscope.http-api.interests-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.http-api.interests :refer [reitit-interests-routes]]
            [kaleidoscope.http-api.kaleidoscope :as kal]
            [kaleidoscope.http-api.tenant :as tenant-mw]
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
                          (fn [middleware] (concat middleware [(kal/inject-components components)
                                          (tenant-mw/wrap-resolve-tenant (tenant-mw/fixed-resolver "andrewslai.com" "andrewslai.com"))])))]
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

;; The full design loop over HTTP with the mock executor: onboard an interest,
;; curate, read the shelf, retune the novelty dial, re-curate, act on a card.
(deftest personal-recommender-end-to-end-test
  (let [db  (embedded-h2/fresh-db!)
        app (test-app {:database db :workflow-executor (workflow-mock/make-mock-executor)})]
    (mw/reset-rate-limits!)
    (let [interest-id
          (get-in (app (-> (mock/request :post "/interests")
                           (mock/json-body {:intent        "Investigative journalism about technology and power"
                                            :taste-profile {:trusted-sources ["PBS Frontline" "The Hill"]
                                                            :novelty-ratio   0.5
                                                            :formats         ["article" "podcast"]}})
                           as-user))
                  [:body :id])]

      (testing "curating fills a finite shelf split by the novelty dial"
        (is (match? {:status 200
                     :body   {:status  "completed"
                              :summary {:total 6 :trusted 3 :novel 3}}}
                    (app (-> (mock/request :post (str "/interests/" interest-id "/curate"))
                             (mock/json-body {})
                             as-user)))))

      (testing "every shelf card carries a why rationale and a trust/novel origin tag"
        (let [{:keys [body]} (app (as-user (mock/request
                                            :get (str "/interests/" interest-id
                                                      "/recommendations?status=shelved"))))]
          (is (= 6 (count body)))
          (is (every? #(and (seq (:why %)) (contains? #{"trusted" "novel"} (:origin %))) body))))

      (testing "the shelf filters by media kind"
        (let [{:keys [body]} (app (as-user (mock/request
                                            :get (str "/interests/" interest-id
                                                      "/recommendations?status=shelved&kind=article"))))]
          (is (seq body))
          (is (every? #(= "article" (:kind %)) body))))

      (testing "retuning the novelty dial to 1.0 makes the next shelf entirely novel"
        (app (-> (mock/request :put (str "/interests/" interest-id))
                 (mock/json-body {:taste-profile {:novelty-ratio 1.0}})
                 as-user))
        (is (match? {:status 200
                     :body   {:summary {:total 6 :trusted 0 :novel 6}}}
                    (app (-> (mock/request :post (str "/interests/" interest-id "/curate"))
                             (mock/json-body {})
                             as-user)))))

      (testing "re-curation replaced the shelf instead of growing it (finite by principle)"
        (is (= 6 (count (:body (app (as-user (mock/request
                                              :get (str "/interests/" interest-id
                                                        "/recommendations?status=shelved")))))))))

      (testing "the reader can queue a card off the shelf"
        (let [rec-id (:id (first (:body (app (as-user (mock/request
                                                       :get (str "/interests/" interest-id
                                                                 "/recommendations?status=shelved")))))))]
          (is (match? {:status 200 :body {:status "queued"}}
                      (app (-> (mock/request :put (str "/interests/" interest-id
                                                       "/recommendations/" rec-id))
                               (mock/json-body {:status "queued"})
                               as-user))))))

      (testing "responding to a step that isn't awaiting input is a 404, not a mutation"
        (is (match? {:status 404}
                    (app (-> (mock/request :post (format "/interests/%s/curation-runs/%s/steps/%s/respond"
                                                         interest-id (random-uuid) (random-uuid)))
                             (mock/json-body {:answers ["x"]})
                             as-user))))))))
