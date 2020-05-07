(ns andrewslai.cljs.core-test
  (:require [cljs.test :as t :refer-macros [deftest is testing]]))

(deftest basic-test
  (testing "Something"
    (is (= 1 1))))
