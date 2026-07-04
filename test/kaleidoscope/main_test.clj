(ns kaleidoscope.main-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [kaleidoscope.http-api.middleware :as mw]
            [kaleidoscope.main :as sut]))

(def base-log-data
  {:level      :info
   :msg_       (delay "hello world")
   :?ns-str    "some.ns"
   :?file      nil
   :?line      42
   :instant    (str (java.time.Instant/now))})

(deftest json-log-output-without-user-context-test
  (testing "Omits user fields, includes trace/span placeholders, when unbound"
    (let [parsed (json/parse-string (#'sut/json-log-output "test" base-log-data) keyword)]
      (is (contains? parsed :trace-id))
      (is (contains? parsed :span-id))
      (is (not (contains? parsed :user-id)))
      (is (= "hello world" (:message parsed)))
      (is (= "test" (:environment parsed))))))

(deftest json-log-output-with-user-context-test
  (testing "Merges bound *user-context* fields into the log line"
    (binding [mw/*user-context* {:user-id "a@test.com" :email "a@test.com" :type :verified-user}]
      (let [parsed (json/parse-string (#'sut/json-log-output "test" base-log-data) keyword)]
        (is (= "a@test.com" (:user-id parsed)))
        (is (= "a@test.com" (:email parsed)))
        (is (= "verified-user" (:type parsed)))))))
