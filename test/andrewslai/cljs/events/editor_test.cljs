(ns andrewslai.cljs.events.editor-test
  (:require [cljs.test :as t :refer-macros [deftest is testing]]
            [andrewslai.cljs.events.editor :as sut]))

(deftest editor-text-changes
  (testing "Editor text changed"
    (is (= 1 1))))
