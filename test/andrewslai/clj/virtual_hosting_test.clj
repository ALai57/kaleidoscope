(ns andrewslai.clj.virtual-hosting-test
  (:require [andrewslai.clj.virtual-hosting :as vh]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [compojure.api.sweet :refer [api GET]]
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

(def gen-domain
  (gen/such-that valid-domain?
                 (gen/fmap (partial apply str)
                           (gen/vector gen-domain-char 1 10))))

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
                          :host     gen-host
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

(defspec virtual-host-regex-matching
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

(deftest host-based-routing
  (let [request-1    (request-map {:host "andrew.com"})
        request-2    (request-map {:host "caheri.and.andrew.com"})

        host-1-regex (-> request-1 req/request-url re-pattern)
        host-2-regex (-> request-2 req/request-url re-pattern)]

    (is (= [host-1-regex {:host 1}]
           (vh/route request-1 {host-1-regex {:host 1}
                                host-2-regex {:host 2}})))
    (is (= [host-2-regex {:host 2}]
           (vh/route request-2 {host-1-regex {:host 1}
                                host-2-regex {:host 2}})))
    (is (= [host-2-regex {:host 2 :priority 10}]
           (vh/route request-2 {#".*"        {:host     1
                                              :priority 100}
                                host-2-regex {:host     2
                                              :priority 10}})))))

(comment
  (def echo-app
    (api
     (GET "/" []
       {:status 200 :body "echo"})))

  (def app-2
    (api
     (GET "/" []
       {:status 200 :body "route-2"})))
  )
