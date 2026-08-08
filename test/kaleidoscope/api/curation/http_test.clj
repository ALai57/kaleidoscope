(ns kaleidoscope.api.curation.http-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.api.curation.http :as http]))

(deftest http-failures-are-data
  (testing "an unresolvable host returns {:status :error} not an exception"
    (is (contains? #{:error :dead} (:status (http/fetch {:url "https://nonexistent.invalid/x"} {}))))))

(deftest ssrf-guard-blocks-private-addresses-test
  (testing "the cloud metadata endpoint is blocked, not fetched"
    (let [result (http/fetch {:url "http://169.254.169.254/latest/meta-data/"} {})]
      (is (not= :ok (:status result)))
      (is (= :error (:status result)))))
  (testing "localhost is blocked"
    (let [result (http/fetch {:url "http://localhost:1/"} {})]
      (is (not= :ok (:status result)))
      (is (= :error (:status result))))))
