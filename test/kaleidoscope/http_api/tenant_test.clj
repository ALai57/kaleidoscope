(ns kaleidoscope.http-api.tenant-test
  (:require [kaleidoscope.http-api.tenant :as sut] [clojure.test :refer [deftest is]]))
(deftest host-resolver-test
  (is (= {:tenant "andrewslai.com" :asset-store "andrewslai.com"}
         (sut/host-resolver {:headers {"host" "andrewslai.com"}}))))
(deftest fixed-resolver-test
  (is (= {:tenant "andrewslai.com" :asset-store "ephemeral-tenant-assets"}
         ((sut/fixed-resolver "andrewslai.com" "ephemeral-tenant-assets")
          {:headers {"host" "kal-eph-xyz.fly.dev"}}))))
(defn- run-resolve [route-data resolve-fn inner]
  (((:compile (sut/wrap-resolve-tenant resolve-fn)) route-data {}) inner))

(deftest wrap-resolve-tenant-test
  (let [captured (atom nil)]
    ;; no route :store → tenant's own store
    ((run-resolve {} (sut/fixed-resolver "a.com" "s")
                  (fn [req] (reset! captured req) {:status 200})) {:headers {"host" "x"}})
    (is (= "a.com" (:tenant @captured)))
    (is (= "s"     (:asset-store @captured))))
  (let [captured (atom nil)]
    ;; route :store overrides the tenant's store (SPA shell), identity unchanged
    ((run-resolve {:store "kaleidoscope.client"} (sut/fixed-resolver "a.com" "s")
                  (fn [req] (reset! captured req) {:status 200})) {:headers {"host" "x"}})
    (is (= "a.com"               (:tenant @captured)))
    (is (= "kaleidoscope.client" (:asset-store @captured)))))
