(ns andrewslai.cljs.core-test
  (:require [cljs.test :as t :refer-macros [deftest is testing run-all-tests]]))

(deftest basic-test
  (testing "Something"
    (is (= 0 1))))

(enable-console-print!)

(defn ^:export run []
  (run-all-tests #"andrewslai.cljs.*-test"))
