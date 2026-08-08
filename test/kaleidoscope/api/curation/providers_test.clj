(ns kaleidoscope.api.curation.providers-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.api.curation.providers :as p]))

(deftest fetch-policy-fallthrough
  (testing "tries policy order, first :ok wins, all-fail -> :dead"
    (with-redefs [p/fetchers {:a (fn [_] {:status :error})
                              :b (fn [_] {:status :ok :final-url "u" :fetcher :b})
                              :c (fn [_] {:status :ok :fetcher :c})}]
      (is (= :b (:fetcher (p/fetch {:url "x"} {:fetch-policy [:a :b :c]}))))
      (is (= :dead (:status (p/fetch {:url "x"} {:fetch-policy [:a]})))))))
