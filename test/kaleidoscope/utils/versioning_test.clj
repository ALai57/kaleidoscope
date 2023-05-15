(ns kaleidoscope.utils.versioning-test
  (:require [kaleidoscope.utils.versioning :as sut]
            [clojure.test :refer :all]
            [matcher-combinators.test :refer [match?]]))

(deftest determine-sha-test
  (is (match? {:revision string?
               :version  string?}
              (sut/get-version-details))))
