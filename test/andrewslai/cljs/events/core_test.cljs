(ns andrewslai.cljs.events.core-test
  (:require [andrewslai.cljs.events.core :as c]
            [cljs.test :refer-macros [deftest is testing]]))

(testing "Set active panel"
  (is (= {:loading? true
          :active-panel :home
          :active-content nil}
         (c/set-active-panel {} [nil :home]))))
