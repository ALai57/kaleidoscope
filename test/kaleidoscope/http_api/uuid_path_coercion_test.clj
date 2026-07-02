(ns kaleidoscope.http-api.uuid-path-coercion-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.http-api.album :refer [reitit-albums-routes]]
            [kaleidoscope.http-api.groups :refer [reitit-groups-routes]]
            [kaleidoscope.http-api.kaleidoscope :as kal]
            [kaleidoscope.http-api.middleware :as mw]
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

(deftest album-nested-path-coercion-test
  (let [app (test-app reitit-albums-routes {:database (embedded-h2/fresh-db!)})]
    (testing "A malformed nested content-id is rejected by coercion before the handler runs"
      (is (match? {:status 400}
                  (app (mock/request :get (str "/albums/" (random-uuid) "/contents/not-a-uuid"))))))

    (testing "Well-formed nested album-id and content-id both coerce and reach the handler"
      (is (match? {:status 404}
                  (app (mock/request :get (str "/albums/" (random-uuid) "/contents/" (random-uuid)))))))))

(deftest workflow-run-step-path-coercion-test
  (let [app (test-app reitit-project-workflow-routes {:database (embedded-h2/fresh-db!)})]
    (testing "A malformed step-run-id three levels deep is rejected by coercion before the handler runs"
      (is (match? {:status 400}
                  (app (mock/request :post (str "/projects/" (random-uuid)
                                                "/workflow-runs/" (random-uuid)
                                                "/steps/not-a-uuid/skip"))))))

    (testing "Well-formed project-id, run-id, and step-run-id all coerce and reach the handler"
      (is (match? {:status 404}
                  (app (mock/request :post (str "/projects/" (random-uuid)
                                                "/workflow-runs/" (random-uuid)
                                                "/steps/" (random-uuid) "/skip"))))))))

(deftest group-membership-path-coercion-test
  (let [app (test-app reitit-groups-routes {:database (embedded-h2/fresh-db!)})]
    (testing "A malformed membership-id is rejected by coercion before the handler runs"
      (is (match? {:status 400}
                  (app (mock/request :delete (str "/groups/" (random-uuid) "/members/not-a-uuid"))))))))
