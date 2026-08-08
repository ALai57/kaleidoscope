(ns kaleidoscope.api.curation.mock-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.api.curation.mock :as mock]
            [kaleidoscope.models.curation :as m]
            [malli.core :as malli]))

(deftest mock-shapes-validate
  (testing "fetch/vet return contract-shaped data"
    (is (malli/validate m/Fetched (mock/fetch {:url "https://example.com/ok/1"} {})))
    (is (= :dead (:status (mock/fetch {:url "https://example.com/dead/1"} {}))))
    (is (malli/validate m/Verdict
          (mock/vet {:title "A" :text "..."} {} (get m/default-rubric "standard"))))))
