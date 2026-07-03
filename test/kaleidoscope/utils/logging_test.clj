(ns kaleidoscope.utils.logging-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [kaleidoscope.http-api.middleware :as mw]
            [kaleidoscope.utils.logging :as sut]))

(def base-log-data
  {:level      :info
   :msg_       (delay "hello world")
   :?ns-str    "some.ns"
   :?file      nil
   :?line      42
   :timestamp_ (delay (str (java.time.Instant/now)))})

(deftest clean-output-fn-without-user-context-test
  (testing "Includes trace/span placeholders but no user fields when unbound"
    (let [output (sut/clean-output-fn base-log-data)]
      (is (string/includes? output "[trace="))
      (is (not (string/includes? output "[user="))))))

(deftest clean-output-fn-with-user-context-test
  (testing "Includes user fields when *user-context* is bound"
    (binding [mw/*user-context* {:user-id "a@test.com" :email "a@test.com" :type :verified-user}]
      (let [output (sut/clean-output-fn base-log-data)]
        (is (string/includes? output "[user=a@test.com email=a@test.com type=:verified-user]"))))))
