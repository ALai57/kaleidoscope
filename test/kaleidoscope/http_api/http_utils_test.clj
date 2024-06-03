(ns kaleidoscope.http-api.http-utils-test
  (:require [kaleidoscope.http-api.http-utils :as sut]
            [clojure.test :refer :all]))


(deftest bucket-name-test
  (are [expected host]
    (= expected (sut/bucket-name {:headers {"host" host}}))

    "andrewslai.com" "andrewslai.com"
    "a.b.c.com"      "a.b.c.com"))
