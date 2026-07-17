(ns kaleidoscope.http-api.tenant-test
  (:require [kaleidoscope.http-api.tenant :as sut] [clojure.test :refer [deftest is]]))
(deftest host-resolver-test
  (is (= {:tenant "andrewslai.com" :asset-store "andrewslai.com"}
         (sut/host-resolver {:headers {"host" "andrewslai.com"}}))))
(deftest fixed-resolver-test
  (is (= {:tenant "andrewslai.com" :asset-store "ephemeral-tenant-assets"}
         ((sut/fixed-resolver "andrewslai.com" "ephemeral-tenant-assets")
          {:headers {"host" "kal-eph-xyz.fly.dev"}}))))
(deftest wrap-resolve-tenant-test
  (let [captured (atom nil)
        handler  ((sut/wrap-resolve-tenant (sut/fixed-resolver "a.com" "s"))
                  (fn [req] (reset! captured req) {:status 200}))]
    (handler {:headers {"host" "x"}})
    (is (= "a.com" (:tenant @captured)))
    (is (= "s"     (:asset-store @captured)))))
