(ns kaleidoscope.http-api.tenant
  "Resolve, once at the edge, the two independent facts a request needs:
  :tenant (identity → DB scoping) and :asset-store (a store name → files)."
  (:require [kaleidoscope.http-api.http-utils :as http-utils]))

(def ephemeral-asset-store
  "Name of the isolated per-env asset store an ephemeral deploy registers and the
  fixed resolver points at. Shared with the s3 launcher in init/env.clj."
  "ephemeral-tenant-assets")

(defn host-resolver
  "Prod/local: identity AND store are the Host header (the tenant's own bucket)."
  [request]
  (let [host (http-utils/get-host request)]
    {:tenant host :asset-store host}))

(defn fixed-resolver
  "Ephemeral: pin identity to `tenant-host` (DB) and the store to `asset-store`
  (the isolated store), independently."
  [tenant-host asset-store]
  (fn [_request] {:tenant tenant-host :asset-store asset-store}))

(defn wrap-resolve-tenant
  "Merge the resolver's {:tenant .. :asset-store ..} onto the request."
  [resolve-fn]
  (fn [handler] (fn [request] (handler (merge request (resolve-fn request))))))
