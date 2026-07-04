(ns kaleidoscope.http-api.workflows-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.http-api.kaleidoscope :as kal]
            [kaleidoscope.http-api.middleware :as mw]
            [kaleidoscope.http-api.workflows :refer [reitit-workflow-routes
                                                     reitit-project-workflow-routes]]
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
     (ring/router [reitit-workflow-routes reitit-project-workflow-routes] config))))

(defn- step
  [overrides]
  (merge {:name "Step" :position 0 :agent-type "coach" :output-kind "text"} overrides))

;; These test the coercion-failure and rate-limit paths only - both run in
;; middleware ahead of the handler (and ahead of any :identity/DB lookup),
;; so none of these requests trigger a Claude call.
(deftest create-workflow-validation-test
  (let [app (test-app {:database (embedded-h2/fresh-db!)})]
    (mw/reset-rate-limits!)
    (testing "More than 20 steps is rejected before it can fan out into 20+ Claude calls"
      (is (match? {:status 400}
                  (app (-> (mock/request :post "/workflows")
                           (mock/json-body {:name "Big" :steps (mapv step (repeat 21 {}))}))))))
    (testing "An unrecognized execution-mode is rejected"
      (is (match? {:status 400}
                  (app (-> (mock/request :post "/workflows")
                           (mock/json-body {:name "Bad" :steps [(step {:execution-mode "loop-forever"})]}))))))
    (testing "An unrecognized output-kind is rejected"
      (is (match? {:status 400}
                  (app (-> (mock/request :post "/workflows")
                           (mock/json-body {:name "Bad" :steps [(step {:output-kind "anything"})]}))))))
    (testing "Missing name is rejected"
      (is (match? {:status 400}
                  (app (mock/request :post "/workflows")))))))

(deftest create-workflow-rate-limit-test
  (let [app     (test-app {:database (embedded-h2/fresh-db!)})
        ;; No name, so coercion always rejects with 400 before reaching the
        ;; DB - the rate limiter runs ahead of coercion, so this still
        ;; exercises it.
        request #(mock/request :post "/workflows")]
    (mw/reset-rate-limits!)
    (dotimes [_ 10]
      (app (request)))
    (testing "11th request from the same IP within the window is rate limited"
      (is (match? {:status 429}
                  (app (request)))))))

(deftest custom-step-validation-test
  (let [app     (test-app {:database (embedded-h2/fresh-db!)})
        run-id  (random-uuid)
        project (random-uuid)]
    (mw/reset-rate-limits!)
    (testing "Oversized custom-step description is rejected before it can inflate a prompt"
      (is (match? {:status 400}
                  (app (-> (mock/request :post (format "/projects/%s/workflow-runs/%s/custom-step" project run-id))
                           (mock/json-body {:description (apply str (repeat 2001 "a"))}))))))))

(deftest advance-rate-limit-test
  (let [app        (test-app {:database (embedded-h2/fresh-db!)})
        project-id (random-uuid)
        run-id     (random-uuid)
        request    #(mock/request :post (format "/projects/%s/workflow-runs/%s/advance" project-id run-id))]
    (mw/reset-rate-limits!)
    (dotimes [_ 10]
      (app (request)))
    (testing "11th request from the same IP within the window is rate limited"
      (is (match? {:status 429}
                  (app (request)))))))

;; PUT /workflows/:workflow-id was the second, unguarded path to the same
;; unbounded-step-count gap POST /workflows caps - this closes that gap.
(deftest update-workflow-validation-test
  (let [app         (test-app {:database (embedded-h2/fresh-db!)})
        workflow-id (random-uuid)]
    (mw/reset-rate-limits!)
    (testing "More than 20 steps via PUT is rejected, same as via POST"
      (is (match? {:status 400}
                  (app (-> (mock/request :put (str "/workflows/" workflow-id))
                           (mock/json-body {:steps (mapv step (repeat 21 {}))}))))))
    (testing "An unrecognized execution-mode via PUT is rejected"
      (is (match? {:status 400}
                  (app (-> (mock/request :put (str "/workflows/" workflow-id))
                           (mock/json-body {:steps [(step {:execution-mode "loop-forever"})]}))))))
    (testing "An empty name (when name is supplied) is still rejected"
      (is (match? {:status 400}
                  (app (-> (mock/request :put (str "/workflows/" workflow-id))
                           (mock/json-body {:name ""}))))))))
