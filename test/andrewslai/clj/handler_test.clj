(ns andrewslai.clj.handler-test
  (:require [andrewslai.clj.auth.keycloak :as keycloak]
            [andrewslai.clj.handler :as h]
            [andrewslai.clj.test-utils :as tu]
            [buddy.auth.middleware :refer [wrap-authentication]]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [matcher-combinators.test]
            [ring.util.codec :as codec]
            [taoensso.timbre :as log]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [compojure.api.sweet :refer [api defroutes GET routes]]
            [clojure.test.check.properties :as prop]
            [ring.util.request :as req]))

(deftest ping-test
  (is (match? {:status 200
               :headers {"Content-Type" string?}
               :body {:revision string?}}
              (tu/http-request :get "/ping" {}))))

(deftest home-test
  (is (match? {:status 200
               :headers {"Content-Type" string?}
               :body seq?}
              (tu/http-request :get "/" {} {:parser (comp tu/->hiccup slurp)}))))

(deftest swagger-test
  (is (match? {:status 200 :body map?}
              (tu/http-request :get "/swagger.json" {}))))

(deftest logging-test
  (let [logging-atom (atom [])]
    (tu/http-request :get "/ping" {:logging (tu/captured-logging logging-atom)})
    (is (= 1 (count @logging-atom)))))

(defn quiet [handler]
  (fn [request]
    (log/with-log-level :fatal
      (handler request))))

(deftest authentication-middleware-test
  (let [app (quiet (wrap-authentication identity (tu/authorized-backend)))]
    (is (match? {:identity {:sub "1234567890"
                            :name "John Doe"
                            :iat 1516239022}}
                (app {:headers {"Authorization" (str "Bearer " tu/valid-token)}})))))

(deftest authentication-middleware-failure-test
  (let [app  (quiet (wrap-authentication identity (tu/unauthorized-backend)))
        resp (app {:headers {"Authorization" (str "Bearer " tu/valid-token)}})]
    (is (nil? (:identity resp)))))


(def gen-protocol
  (s/gen :network/protocol))

(def gen-domain
  (gen/fmap (partial apply str)
            (gen/vector gen/char 1 10)))

(def gen-host
  (gen/fmap (fn [xs] (string/join "." xs))
            (gen/vector gen-domain 1 10)))

(def gen-port
  (s/gen :network/port))

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
    (is (h/valid-virtual-host? request (-> request
                                           get-host
                                           java.util.regex.Pattern/quote
                                           re-pattern)))))

(defspec select-virtual-host-spec
  (prop/for-all [request-1 gen-request
                 request-2 gen-request]
    (let [host-1-url (-> request-1
                         get-host
                         java.util.regex.Pattern/quote
                         re-pattern)
          host-2-url (-> request-2
                         get-host
                         java.util.regex.Pattern/quote
                         re-pattern)]
      (is (= [host-1-url {:host 1}]
             (h/select-virtual-host request-1 {host-1-url {:host 1}
                                               host-2-url {:host 2}})))
      (is (= [host-2-url {:host 2}]
             (h/select-virtual-host request-2 {host-1-url {:host 1}
                                               host-2-url {:host 2}})))
      (is (= [host-2-url {:host 2 :priority 10}]
             (h/select-virtual-host request-2 {#".*"      {:host     1
                                                           :priority 100}
                                               host-2-url {:host     2
                                                           :priority 10}}))))))

