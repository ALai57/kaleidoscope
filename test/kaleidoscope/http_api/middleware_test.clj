(ns kaleidoscope.http-api.middleware-test
  (:require [cheshire.core :as json]
            [clojure.test :refer :all]
            [kaleidoscope.http-api.auth.buddy-backends :as bb]
            [kaleidoscope.http-api.middleware :as sut]
            [kaleidoscope.test-main :as tm]
            [kaleidoscope.test-utils :as tu]
            [lambdaisland.deep-diff2 :as ddiff]
            [lambdaisland.deep-diff2.diff-impl :refer [map->Deletion map->Insertion map->Mismatch]]
            [reitit.ring :as ring]
            [matcher-combinators.test :refer [match?]]
            [ring.mock.request :as mock]
            [taoensso.timbre :as log]
            [kaleidoscope.http-api.middleware :as mw]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level tm/*test-log-level*
      (f))))

(deftest remove-unchanged-basic-test
  (testing "Addition"
    (is (= {(map->Insertion {:+ :b}) 2}
           (sut/remove-unchanged (ddiff/diff {}
                                             {:b 2})))))
  (testing "Deletion"
    (is (= {(map->Deletion  {:- :a}) 1}
           (sut/remove-unchanged (ddiff/diff {:a 1}
                                             {})))))

  (testing "Mismatch"
    (is (= {:a (map->Mismatch {:- 1 :+ 2})}
           (sut/remove-unchanged (ddiff/diff {:a 1}
                                             {:a 2}))))))

(deftest remove-unchanged-collections
  (is (= [(map->Deletion {:- 2}) (map->Insertion {:+ 4})]
         (sut/remove-unchanged (ddiff/diff [1 2 3]
                                           [1 3 4])))))

(deftest elements-are-not-changed
  (are [description duplicated-element]
    (testing description
      (is (nil?
           (sut/remove-unchanged (ddiff/diff duplicated-element
                                             duplicated-element)))))

    "Basic key-value pair is removed"
    {:a 1}

    "Empty map is removed"
    {:a {} :b {}}

    "Map is removed"
    {:a {:mw :b}}

    "Vector with empty maps is removed"
    {:a [{}]}

    "Vector with populated map is removed"
    {:a [{:a #{:b}}]}))

(deftest remove-unchanged-with-map
  (is (= {:b                        (map->Mismatch {:- 3 :+ 2})
          (map->Deletion {:- :foo}) "bar"
          (map->Insertion {:+ :c})  3
          :d                        {:e (map->Mismatch {:- "f" :+ "g"})}}
         (sut/remove-unchanged (ddiff/diff {:a   "1"
                                            :b   3
                                            :foo "bar"
                                            :d   {:e "f"}
                                            :x   [{:y :z}]}
                                           {:a "1"
                                            :b 2
                                            :c 3
                                            :d {:e "g"}
                                            :x [{:y :z}]})))))

(deftest standard-stack-test
  (let [captured-request (atom nil)
        app              ((apply comp sut/standard-stack) (fn [req]
                                                            (reset! captured-request req)
                                                            {:status 200
                                                             :body   {:foo "bar"}}))]
    (is (match? {:status  200
                 :headers {"Content-Type" #"application/json"}
                 :body    (json/generate-string {:foo "bar"})}
                (app (mock/request :get "/"))))
    (is (match? {:uri        "/"
                 :request-id string?}
                @captured-request))))

(deftest auth-stack-happy-path-test
  (let [captured-request (atom nil)
        mw-stack         (apply comp (sut/auth-stack (bb/authenticated-backend {:realm_access {:roles ["myrole"]}})
                                                     (tu/restricted-access "myrole")))
        app              (mw-stack (fn [req]
                                     (reset! captured-request req)
                                     {:status 200
                                      :body   {:foo "bar"}}))]
    (is (match? {:status 200
                 :body   {:foo "bar"}}
                (app (-> (mock/request :get "/")
                         (mock/header "Authorization" "Bearer x")))))
    (is (match? {:identity {:realm_access {:roles ["myrole"]}}}
                @captured-request))))

(deftest auth-stack-wrong-role-test
  (let [captured-request (atom nil)
        mw-stack         (apply comp (sut/auth-stack (bb/authenticated-backend {:realm_access {:roles ["myrole"]}})
                                                     (tu/restricted-access "wrongrole")))
        app              (mw-stack (fn [req]
                                     (reset! captured-request req)
                                     {:status 200
                                      :body   {:foo "bar"}}))]
    (is (match? {:status 401}
                (app (-> (mock/request :get "/")
                         (mock/header "Authorization" "Bearer x")))))
    (is (nil? (:identity @captured-request)))))

;;
;; Reitit tests
;;
(def identity-route
  ["/identity" {:get {:parameters {:query [:map
                                           [:baz [:= "quxx"]]]
                                   :body [:map
                                          [:foo [:= "bar"]]]}
                      :handler    (fn [request]
                                    {:status 200
                                     :body   (select-keys request [:parameters :params :body-params :form-params :query-params])})}}])

(defn clojurize
  [{:keys [body] :as ring-request}]
  (update ring-request :body (fn [x]
                               (-> x
                                   slurp
                                   (json/parse-string keyword)))))

(deftest reitit-configuration-test
  (let [app (ring/ring-handler (ring/router identity-route mw/reitit-configuration))]
    (testing "Coerced requests are put in the `:parameters` key"
      (is (match? {:status 200
                   :body   {:parameters {:query {:baz "quxx"}
                                         :body  {:foo "bar"}}}}
                  (-> (mock/request :get "/identity")
                      (mock/query-string {:baz "quxx"})
                      (mock/json-body {:foo :bar})
                      app
                      clojurize))))

    (testing "Invalid input causes a coercion failure and 400"
      (is (match? {:status 400
                   :body   {:humanized {:baz ["missing required key"]}}}
                  (-> (-> (mock/request :get "/identity")
                          (mock/json-body {:invalid :param})
                          app
                          clojurize)))))))
