(ns kaleidoscope.http-api.http-utils-test
  (:require [kaleidoscope.http-api.http-utils :as sut]
            [clojure.test :refer :all]))


(deftest site-value-test
  (is (= "andrewslai.com" (sut/site-value {:tenant "andrewslai.com" :headers {"host" "x"}})))
  (is (= "x"              (sut/site-value {:headers {"host" "x"}}))))          ; fallback for pre-resolver paths

(deftest asset-store-test
  (is (= "kaleidoscope.client" (sut/asset-store {sut/forced-store-key "kaleidoscope.client" :asset-store "a"})))
  (is (= "a"                   (sut/asset-store {:asset-store "a"})))
  (is (nil? (sut/asset-store {}))))                                             ; no store, no fallback
