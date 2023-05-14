(ns kaleidoscope.http-api.http-utils-test
  (:require [kaleidoscope.http-api.http-utils :as sut]
            [clojure.test :refer :all]))


(deftest bucket-name-test
  (are [expected host]
    (= expected (sut/bucket-name {:headers {"host" host}}))

    "andrewslai" "andrewslai.com"
    "a.b.c"      "a.b.c.com"))
