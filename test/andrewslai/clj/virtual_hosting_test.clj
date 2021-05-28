(ns andrewslai.clj.virtual-hosting-test
  (:require [andrewslai.clj.test-utils :as tu]
            [andrewslai.clj.virtual-hosting :as vh]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [clojure.test :refer [are deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [compojure.api.sweet :refer [api GET]]
            [matcher-combinators.test]
            [ring.util.request :as req]))

(def gen-protocol
  (s/gen :network/protocol))

;; According to Mozilla, labels/components follow the TLD and valid strings are
;;  between 1 and 63 chars in length, including chars [A-Za-z0-9-]
;; https://developer.mozilla.org/en-US/docs/Learn/Common_questions/What_is_a_domain_name
(def gen-domain-char
  (gen/fmap char
            (gen/one-of [(gen/choose 48 57)
                         (gen/choose 65 90)
                         (gen/choose 97 122)
                         (gen/return 45)])))

;; Labels cannot begin or end with `-`
(defn valid-domain?
  [s]
  (and (not (string/ends-with? s "-"))
       (not (string/starts-with? s "-"))))

(defn append
  [s c]
  (str s c))

(defn prepend
  [s c]
  (str c s))

(def gen-domain
  (gen/fmap (fn [xs]
              (let [s (apply str xs)]
                (cond-> s
                  (string/starts-with? s "-") (prepend (gen/generate gen/char-alphanumeric))
                  (string/ends-with? s "-")   (append (gen/generate gen/char-alphanumeric)))))
            (gen/vector gen-domain-char 1 10)))

(def gen-host
  (gen/fmap (fn [xs] (string/join "." xs))
            (gen/vector gen-domain 1 10)))

(def gen-port
  (s/gen :network/port))

(def gen-delims
  (gen/elements [":" "/" "?" "#" "[" "]" "@"]))

(def gen-sub-delims
  (gen/elements ["!" "$" "&" "'" "(" ")" "*" "+" "," ";" "="]))

(def gen-reserved-char
  (gen/one-of [gen-delims gen-sub-delims]))

(def gen-endpoint
  (gen/fmap (fn [xs] (str "/" (string/join "/" xs)))
            (gen/vector gen/string)))

(def gen-url
  (gen/fmap (fn [{:keys [protocol host port endpoint]}]
              (format "%s://%s:%s%s" protocol host port endpoint))
            (gen/hash-map :protocol gen-protocol
                          :app     gen-host
                          :port     gen-port
                          :endpoint gen-endpoint)))

(def gen-request
  (gen/fmap (fn [[protocol host endpoint]]
              {:scheme protocol
               :headers {"host" host}
               :uri endpoint})
            (gen/tuple gen-protocol gen-host gen-endpoint)))

(defn get-host
  [request]
  (get-in request [:headers "host"]))

(defspec virtual-host-matching
  (prop/for-all [request gen-request]
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

    (is (thrown? IllegalArgumentException (app {:scheme         "https"
                                                :request-method :get
                                                :uri            "/"
                                                :headers        {"host" "bad"}})))))
