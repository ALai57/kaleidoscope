(ns kaleidoscope.utils.core-test
  (:require [kaleidoscope.utils.core :as sut]
            [clojure.test :refer [deftest is]]))


(deftest deep-merge-test
  (is (= {:max-age 3600 :secure true}
         (sut/deep-merge {:max-age 3600 :secure false}
                         {:secure true})))
  (is (= {:cookie-attrs {:max-age 3600 :secure true}
          :store        {:hi  "there"
                         :bye "there"}
          :stuff        "yes"
          :more         "hi"}
         (sut/deep-merge {:cookie-attrs {:max-age 3600 :secure false}
                          :store        {:hi "there"}
                          :stuff        "yes"}
                         {:cookie-attrs {:secure true}
                          :store        {:bye "there"}
                          :more          "hi"}))))
