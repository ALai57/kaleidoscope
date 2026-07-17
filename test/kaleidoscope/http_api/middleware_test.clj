(ns kaleidoscope.http-api.middleware-test
  (:require [cheshire.core :as json]
            [clojure.test :refer :all]
            [kaleidoscope.http-api.auth.buddy-backends :as bb]
            [kaleidoscope.http-api.http-utils :as http-utils]
            [kaleidoscope.http-api.middleware :as sut]
            [kaleidoscope.test-main :as tm]
            [kaleidoscope.test-utils :as tu]
            [lambdaisland.deep-diff2 :as ddiff]
            [lambdaisland.deep-diff2.diff-impl :refer [map->Deletion map->Insertion map->Mismatch]]
            [reitit.ring :as ring]
            [matcher-combinators.test :refer [match?]]
            [ring.mock.request :as mock]
            [taoensso.timbre :as log]
            [kaleidoscope.http-api.middleware :as mw])
  (:import
   (lambdaisland.deep_diff2.diff_impl Deletion Insertion Mismatch)))

;; https://gist.github.com/hsartoris-bard/856d79d3a13f6cafaaa6e5079c76cd97
(def ^:private mismatch? (partial instance? Mismatch))
(def ^:private deletion?  (partial instance? Deletion))
(def ^:private insertion? (partial instance? Insertion))
(def ^:private diff?      (some-fn mismatch? deletion? insertion?))

(defn- remove-unchanged
  [x]
  (cond
    (diff? x)      x
    (map-entry? x) (let [[k v] x]
                     (cond
                       (diff? k)                              x
                       (diff? v)                              x
                       ((every-pred coll? not-empty) v)       (when-let [result (remove-unchanged v)]
                                                                [k result])))
    (coll? x) (when-let [result (->> x
                                     (map remove-unchanged)
                                     (filter not-empty)
                                     (seq))]
                (into (empty x) result))))

(use-fixtures :once
  (fn [f]
    (log/with-min-level tm/*test-log-level*
      (f))))

(deftest remove-unchanged-basic-test
  (testing "Addition"
    (is (= {(map->Insertion {:+ :b}) 2}
           (remove-unchanged (ddiff/diff {}
                                             {:b 2})))))
  (testing "Deletion"
    (is (= {(map->Deletion  {:- :a}) 1}
           (remove-unchanged (ddiff/diff {:a 1}
                                             {})))))

  (testing "Mismatch"
    (is (= {:a (map->Mismatch {:- 1 :+ 2})}
           (remove-unchanged (ddiff/diff {:a 1}
                                             {:a 2}))))))

(deftest remove-unchanged-collections
  (is (= [(map->Deletion {:- 2}) (map->Insertion {:+ 4})]
         (remove-unchanged (ddiff/diff [1 2 3]
                                           [1 3 4])))))

(deftest elements-are-not-changed
  (are [description duplicated-element]
    (testing description
      (is (nil?
           (remove-unchanged (ddiff/diff duplicated-element
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
         (remove-unchanged (ddiff/diff {:a   "1"
                                            :b   3
                                            :foo "bar"
                                            :d   {:e "f"}
                                            :x   [{:y :z}]}
                                           {:a "1"
                                            :b 2
                                            :c 3
                                            :d {:e "g"}
                                            :x [{:y :z}]})))))

#_(deftest standard-stack-test
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
        mw-stack         (apply comp (sut/auth-stack (bb/authenticated-backend {:email          "a@test.com"
                                                                                  :email_verified true
                                                                                  :realm_access   {:roles ["myrole"]}})
                                                     (tu/restricted-access "myrole")))
        app              (mw-stack (fn [req]
                                     (reset! captured-request req)
                                     {:status 200
                                      :body   {:foo "bar"}}))]
    (is (match? {:status 200
                 :body   {:foo "bar"}}
                (app (-> (mock/request :get "/")
                         (mock/header "Authorization" "Bearer x")))))
    (is (match? {:identity {:type :verified-user :roles #{"myrole"}}}
                @captured-request))))

(deftest auth-stack-unverified-user-test
  (let [captured-request (atom nil)
        mw-stack         (apply comp (sut/auth-stack (bb/authenticated-backend {:realm_access {:roles ["myrole"]}})
                                                     (tu/restricted-access "myrole")))
        app              (mw-stack (fn [req]
                                     (reset! captured-request req)
                                     {:status 200
                                      :body   {:foo "bar"}}))]
    (testing "An unverified user is rejected even with the correct role"
      (is (match? {:status 401}
                  (app (-> (mock/request :get "/")
                           (mock/header "Authorization" "Bearer x")))))
      (is (nil? (:identity @captured-request))))))

(deftest wrap-bind-user-context-verified-user-test
  (let [captured-user-context (atom :not-set)
        mw-stack              (apply comp (sut/auth-stack (bb/authenticated-backend {:email          "a@test.com"
                                                                                      :email_verified true})
                                                           tu/public-access))
        app                   (mw-stack (fn [req]
                                          (reset! captured-user-context mw/*user-context*)
                                          {:status 200
                                           :body   {:foo "bar"}}))]
    (app (-> (mock/request :get "/")
             (mock/header "Authorization" "Bearer x")))
    (is (match? {:user-id "a@test.com"
                :email   "a@test.com"
                :type    :verified-user}
               @captured-user-context))))

(deftest wrap-bind-user-context-service-account-test
  (let [captured-user-context (atom :not-set)
        mw-stack              (apply comp (sut/auth-stack (bb/authenticated-backend {:gty "client-credentials"
                                                                                      :sub "service-client-123"})
                                                           tu/public-access))
        app                   (mw-stack (fn [req]
                                          (reset! captured-user-context mw/*user-context*)
                                          {:status 200
                                           :body   {:foo "bar"}}))]
    (app (-> (mock/request :get "/")
             (mock/header "Authorization" "Bearer x")))
    (is (match? {:user-id "service-client-123"
                :type    :service-account}
               @captured-user-context))))

(deftest wrap-bind-user-context-unauthenticated-test
  (let [captured-user-context (atom :not-set)
        mw-stack              (apply comp (sut/auth-stack bb/unauthenticated-backend
                                                           tu/public-access))
        app                   (mw-stack (fn [req]
                                          (reset! captured-user-context mw/*user-context*)
                                          {:status 200
                                           :body   {:foo "bar"}}))]
    (app (mock/request :get "/"))
    (is (nil? @captured-user-context))))

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

(deftest wrap-exception-reporter-test
  (let [reported         (atom [])
        report-fn        (fn [e] (swap! reported conj e))
        throwing-handler (fn [_] (throw (Exception. "test error")))
        app              ((mw/wrap-exception-reporter report-fn) throwing-handler)]
    (testing "Returns 500 on unhandled exception"
      (is (match? {:status 500}
                  (log/with-min-level :fatal
                    (app (mock/request :get "/test"))))))
    (testing "Calls the report function with the exception"
      (is (= 1 (count @reported)))
      (is (= "test error" (.getMessage (first @reported)))))))

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

(def rate-limited-route
  ["/limited" {:get {:rate-limit {:max-requests 3 :window-ms 60000}
                     :handler    (fn [_] {:status 200 :body {:ok true}})}}])

(deftest wrap-rate-limit-test
  (let [app (ring/ring-handler (ring/router rate-limited-route mw/reitit-configuration))]
    (mw/reset-rate-limits!)
    (testing "Requests within the limit succeed"
      (dotimes [_ 3]
        (is (match? {:status 200} (app (mock/request :get "/limited"))))))
    (testing "The next request from the same IP is rejected"
      (is (match? {:status 429} (app (mock/request :get "/limited")))))))

(deftest wrap-rate-limit-per-ip-test
  (let [app (ring/ring-handler (ring/router rate-limited-route mw/reitit-configuration))]
    (mw/reset-rate-limits!)
    (dotimes [_ 3]
      (app (mock/request :get "/limited")))
    (testing "A different IP is not affected by another IP's usage"
      (is (match? {:status 200}
                  (app (-> (mock/request :get "/limited")
                           (assoc :remote-addr "10.0.0.1"))))))))

(deftest wrap-rate-limit-unaffected-routes-test
  (let [unlimited-route ["/unlimited" {:get {:handler (fn [_] {:status 200 :body {:ok true}})}}]
        app             (ring/ring-handler (ring/router unlimited-route mw/reitit-configuration))]
    (mw/reset-rate-limits!)
    (testing "Routes without :rate-limit route data are never throttled"
      (dotimes [_ 50]
        (is (match? {:status 200} (app (mock/request :get "/unlimited"))))))))

;; The bucket key must be per-route, not per literal request path - keying
;; on (:uri request) would give every distinct :id its own independent
;; bucket, letting a caller who owns N resources get N times the effective
;; limit on any parameterized route.
(def parameterized-rate-limited-route
  ["/limited/:id" {:get {:rate-limit {:max-requests 3 :window-ms 60000}
                        :handler    (fn [_] {:status 200 :body {:ok true}})}}])

(deftest wrap-rate-limit-shares-bucket-across-path-params-test
  (let [app (ring/ring-handler (ring/router parameterized-rate-limited-route mw/reitit-configuration))]
    (mw/reset-rate-limits!)
    (testing "Requests to different concrete ids under the same route share one bucket"
      (is (match? {:status 200} (app (mock/request :get "/limited/aaa"))))
      (is (match? {:status 200} (app (mock/request :get "/limited/bbb"))))
      (is (match? {:status 200} (app (mock/request :get "/limited/ccc")))))
    (testing "A 4th request, even against a brand-new id, is rejected"
      (is (match? {:status 429} (app (mock/request :get "/limited/never-seen-before")))))))

(defn- run-force [store inner]
  (((:compile sut/wrap-force-store) {:store store} {}) inner))

(deftest force-store-test
  (let [c (atom nil)] ((run-force "kaleidoscope.client" (fn [r] (reset! c r) {})) {})
       (is (= "kaleidoscope.client" (:asset-store @c))))
  (let [c (atom nil)] ((run-force nil (fn [r] (reset! c r) {})) {})
       (is (nil? (:asset-store @c)))))

(deftest wrap-kebab-case-headers-test
  (let [c       (atom nil)
        handler (sut/wrap-kebab-case-headers (fn [r] (reset! c r) {:status 200}))]
    (handler {:headers {"If-None-Match" "v1"}})
    (is (= {"if-none-match" "v1"} (:headers @c)))))

(deftest wrap-rate-limit-separate-routes-dont-share-a-bucket-test
  (let [routes ["" {}
                ["/limited-a" {:get {:rate-limit {:max-requests 1 :window-ms 60000}
                                     :handler    (fn [_] {:status 200 :body {:ok true}})}}]
                ["/limited-b" {:get {:rate-limit {:max-requests 1 :window-ms 60000}
                                     :handler    (fn [_] {:status 200 :body {:ok true}})}}]]
        app    (ring/ring-handler (ring/router routes mw/reitit-configuration))]
    (mw/reset-rate-limits!)
    (is (match? {:status 200} (app (mock/request :get "/limited-a"))))
    (testing "A different route entirely isn't affected by another route's usage"
      (is (match? {:status 200} (app (mock/request :get "/limited-b")))))))
