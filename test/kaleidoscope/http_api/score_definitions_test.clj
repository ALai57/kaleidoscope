(ns kaleidoscope.http-api.score-definitions-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.http-api.kaleidoscope :as kal]
            [kaleidoscope.http-api.middleware :as mw]
            [kaleidoscope.http-api.score-definitions :refer [reitit-score-definition-routes]]
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
     (ring/router [reitit-score-definition-routes] config))))

;; These test the coercion-failure path only - it runs before the handler
;; (and before any :identity/DB lookup), so none of these requests trigger
;; a Claude call.
(deftest create-score-definition-validation-test
  (let [app (test-app {:database (embedded-h2/fresh-db!)})]
    (testing "Missing name is rejected"
      (is (match? {:status 400}
                  (app (-> (mock/request :post "/score-definitions")
                           (mock/json-body {:description "no name"}))))))
    (testing "More than 20 dimensions is rejected before it can inflate a scoring prompt"
      (is (match? {:status 400}
                  (app (-> (mock/request :post "/score-definitions")
                           (mock/json-body {:name       "Big"
                                            :dimensions (vec (repeat 21 {:name "D" :criteria "c"}))}))))))
    (testing "Oversized dimension criteria is rejected"
      (is (match? {:status 400}
                  (app (-> (mock/request :post "/score-definitions")
                           (mock/json-body {:name       "Big"
                                            :dimensions [{:name "D" :criteria (apply str (repeat 2001 "a"))}]}))))))))

(deftest update-score-definition-validation-test
  (let [app         (test-app {:database (embedded-h2/fresh-db!)})
        definition-id (random-uuid)]
    (testing "More than 20 dimensions via PUT is rejected, same as via POST"
      (is (match? {:status 400}
                  (app (-> (mock/request :put (str "/score-definitions/" definition-id))
                           (mock/json-body {:dimensions (vec (repeat 21 {:name "D" :criteria "c"}))}))))))))
