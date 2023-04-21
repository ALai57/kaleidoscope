(ns kaleidoscope.http-api.virtual-hosting-test
  (:require [clojure.test :refer [are deftest is use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [compojure.api.sweet :refer [GET]]
            [kaleidoscope.generators.networking :as gen-net]
            [kaleidoscope.http-api.virtual-hosting :as vh]
            [kaleidoscope.test-main :as tm]
            [matcher-combinators.test :refer [match?]]
            [ring.util.request :as req]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level tm/*test-log-level*
      (f))))

(defn get-host
  [request]
  (get-in request [:headers "host"]))

(defspec virtual-host-matching
  (prop/for-all [request gen-net/gen-request]
    (is (vh/matching-url? request (-> request
                                      get-host
                                      re-pattern)))))

(defn request-map
  [{:keys [scheme host uri]
    :or {scheme "https"
         host   "andrew.com"
         uri    "/"}}]
  {:scheme scheme
   :headers {"host" host}
   :uri uri})

(deftest select-app-test
  (let [request-1    (request-map {:host "foo.com"})
        request-2    (request-map {:host "bar.com"})

        host-1-regex (-> request-1 req/request-url re-pattern)
        host-2-regex (-> request-2 req/request-url re-pattern)]

    (is (= 1 (vh/select-app request-1 {host-1-regex {:app 1}
                                       host-2-regex {:app 2}})))

    (is (= 2 (vh/select-app request-2 {host-1-regex {:app 1}
                                       host-2-regex {:app 2}})))

    (is (= 2 (vh/select-app request-1 {#".*"        {:app 1 :priority 100}
                                       host-1-regex {:app 2 :priority 10}})))

    (is (= 1 (vh/select-app request-1 {#".*"        {:app 1 :priority 10}
                                       host-1-regex {:app 2 :priority 100}})))))

(defn dummy-app
  [response]
  (GET "/" []
    {:status 200 :body response}))

(deftest routing-test
  (let [apps {#"foo.com"     {:priority 100 :app (dummy-app :foo)}
              #"baz.foo.com" {:priority  10 :app (dummy-app :baz.foo)}
              #"bar.com"     {:priority   0 :app (dummy-app :bar)}}]

    (are [method host expected]
      (is (match? expected
                  ((vh/host-based-routing apps) {:scheme         "https"
                                                 :request-method method
                                                 :uri            "/"
                                                 :headers        {"host" host}})))

      :get  "foo.com"     {:status 200 :body :foo}
      :get  "baz.foo.com" {:status 200 :body :baz.foo}
      :post "baz.foo.com" nil)))

(deftest routing-failure-test
  (let [app (vh/host-based-routing
             {#"foo.com" {:priority 100 :app (dummy-app :foo)}})]

    (log/with-min-level :fatal
      (is (thrown? IllegalArgumentException (app {:scheme         "https"
                                                  :request-method :get
                                                  :uri            "/"
                                                  :headers        {"host" "bad"}}))))))
