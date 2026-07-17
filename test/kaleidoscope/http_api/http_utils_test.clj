(ns kaleidoscope.http-api.http-utils-test
  (:require [kaleidoscope.http-api.http-utils :as sut]
            [clojure.test :refer :all]))


(deftest get-tenant-test
  (is (= "andrewslai.com" (sut/get-tenant {:tenant "andrewslai.com" :headers {"host" "x"}})))
  (is (nil? (sut/get-tenant {:headers {"host" "x"}}))))                        ; no Host fallback — the resolver always sets :tenant

(deftest asset-store-test
  (is (= "kaleidoscope.client" (sut/asset-store {sut/forced-store-key "kaleidoscope.client" :asset-store "a"})))
  (is (= "a"                   (sut/asset-store {:asset-store "a"})))
  (is (nil? (sut/asset-store {}))))                                             ; no store, no fallback
