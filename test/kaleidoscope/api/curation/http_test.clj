(ns kaleidoscope.api.curation.http-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.api.curation.http :as http]))

(deftest http-failures-are-data
  (testing "an unresolvable host returns {:status :error} not an exception"
    (is (contains? #{:error :dead} (:status (http/fetch {:url "https://nonexistent.invalid/x"} {}))))))
