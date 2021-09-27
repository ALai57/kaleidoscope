(ns andrewslai.clj.utils.core-test
  (:require [andrewslai.clj.utils.core :refer :all]
            [clojure.test :refer [deftest is]]))


(deftest deep-merge-test
  (is (= {:max-age 3600 :secure true}
         (deep-merge {:max-age 3600 :secure false}
                     {:secure true})))
  (is (= {:cookie-attrs {:max-age 3600 :secure true}
          :store        {:hi  "there"
                         :bye "there"}
          :stuff        "yes"
          :more         "hi"}
         (deep-merge {:cookie-attrs {:max-age 3600 :secure false}
                      :store        {:hi "there"}
                      :stuff        "yes"}
                     {:cookie-attrs {:secure true}
                      :store        {:bye "there"}
                      :more          "hi"}))))
