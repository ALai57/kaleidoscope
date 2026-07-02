(ns kaleidoscope.http-api.body-param-coercion-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.http-api.kaleidoscope :as kal]
            [kaleidoscope.http-api.middleware :as mw]
            [kaleidoscope.http-api.projects :refer [reitit-projects-routes]]
            [kaleidoscope.http-api.tasks :refer [reitit-task-routes]]
            [kaleidoscope.http-api.workflows :refer [reitit-project-workflow-routes]]
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
  [routes components]
  (let [config (update-in mw/reitit-configuration
                          [:data :middleware]
                          (fn [middleware] (concat middleware [(kal/inject-components components)])))]
    (ring/ring-handler
     (ring/router [routes] config))))

(deftest score-definition-ids-coercion-test
  (let [app (test-app reitit-projects-routes {:database (embedded-h2/fresh-db!)})]
    (testing "A malformed UUID inside definition-ids is rejected by coercion"
      (is (match? {:status 400}
                  (app (-> (mock/request :post (str "/projects/" (random-uuid) "/scores"))
                           (mock/json-body {:definition-ids ["not-a-uuid"]}))))))

    (testing "Well-formed definition-ids coerce and reach the handler"
      (is (match? {:status 404}
                  (app (-> (mock/request :post (str "/projects/" (random-uuid) "/scores"))
                           (mock/json-body {:definition-ids [(str (random-uuid))]}))))))))

(deftest task-reorder-body-coercion-test
  (let [app (test-app reitit-task-routes {:database (embedded-h2/fresh-db!)})]
    (testing "A malformed id in the reorder array is rejected by coercion"
      (is (match? {:status 400}
                  (app (-> (mock/request :put (str "/projects/" (random-uuid) "/tasks/reorder"))
                           (mock/json-body [{:id "not-a-uuid" :position 0}]))))))

    (testing "A well-formed reorder array coerces and reaches the handler"
      (is (match? {:status 404}
                  (app (-> (mock/request :put (str "/projects/" (random-uuid) "/tasks/reorder"))
                           (mock/json-body [{:id (str (random-uuid)) :position 0}]))))))))

(deftest workflow-run-body-coercion-test
  (let [app (test-app reitit-project-workflow-routes {:database (embedded-h2/fresh-db!)})]
    (testing "A malformed workflow-id is rejected by coercion"
      (is (match? {:status 400}
                  (app (-> (mock/request :post (str "/projects/" (random-uuid) "/workflow-runs"))
                           (mock/json-body {:workflow-id "not-a-uuid"}))))))

    (testing "An out-of-enum mode is rejected by coercion"
      (is (match? {:status 400}
                  (app (-> (mock/request :post (str "/projects/" (random-uuid) "/workflow-runs"))
                           (mock/json-body {:mode "sprint"}))))))

    (testing "A well-formed body coerces and reaches the handler"
      (is (match? {:status 404}
                  (app (-> (mock/request :post (str "/projects/" (random-uuid) "/workflow-runs"))
                           (mock/json-body {:workflow-id (str (random-uuid))
                                            :mode        "autonomous"
                                            :scrutiny    "rigorous"
                                            :target-score 7.5}))))))))
