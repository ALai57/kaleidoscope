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
