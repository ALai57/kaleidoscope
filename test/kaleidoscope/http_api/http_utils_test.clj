(ns kaleidoscope.http-api.http-utils-test
  (:require [kaleidoscope.http-api.http-utils :as sut]
            [clojure.test :refer :all]))


(deftest get-tenant-test
  (is (= {:hostname "andrewslai.com" :asset-store "s"}
         (sut/get-tenant {:tenant {:hostname "andrewslai.com" :asset-store "s"}})))
  (is (nil? (sut/get-tenant {}))))

(deftest tenant-hostname-test
  (is (= "andrewslai.com" (sut/tenant-hostname {:tenant {:hostname "andrewslai.com"}})))
  (is (nil? (sut/tenant-hostname {}))))                                        ; no tenant → nil (no Host fallback)

(deftest asset-store-test
  (is (= "a" (sut/asset-store {:tenant {:asset-store "a"}})))
  (is (nil? (sut/asset-store {}))))                                             ; no store set → nil (no fallback)
