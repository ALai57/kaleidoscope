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
  "Resolve, once at the edge, the tenant (:tenant, for DB scoping) and where its
  files come from (:asset-store) — the two are inextricably linked, so one
  middleware owns both. A route may name a shared store via :store (the SPA
  shell's kaleidoscope.client, shared across tenants), which overrides the
  tenant's own store; every other route serves from the tenant's store. Compile
  middleware so it can read the route's :store."
  [resolve-fn]
  {:name    ::wrap-resolve-tenant
   :compile (fn [{:keys [store]} _opts]
              (fn [handler]
                (fn [request]
                  (let [{:keys [tenant asset-store]} (resolve-fn request)]
                    (handler (assoc request
                                    :tenant      tenant
                                    :asset-store (or store asset-store)))))))})
