(ns kaleidoscope.http-api.tenant
  "Resolve, once at the edge, the tenant a request is for: a value
  {:hostname .. :tenant-name .. :asset-store ..} placed on the request under
  :tenant. :hostname scopes DB queries; :asset-store selects the file store;
  :tenant-name identifies the tenant."
  (:require [kaleidoscope.http-api.http-utils :as http-utils]))

(def ephemeral-asset-store
  "Name of the isolated per-env asset store an ephemeral deploy registers and the
  fixed resolver points at. Shared with the s3 launcher in init/env.clj."
  "ephemeral-tenant-assets")

(defn host-resolver
 "Prod/local: the tenant is the request's Host header — hostname, name, and
  store all derive from it (the tenant's own bucket)."
  [request]
  (let [host (http-utils/get-host request)]
    {:hostname host :tenant-name host :asset-store host}))

(defn fixed-resolver
  "Ephemeral: pin the tenant to `tenant-host` (hostname + name) and its store to
  `asset-store` (the isolated store), independently."
  [tenant-host asset-store]
  (fn [_request] {:hostname tenant-host :tenant-name tenant-host :asset-store asset-store}))

(defn wrap-resolve-tenant
  "Resolve the tenant once at the edge and place it on the request under :tenant
  as {:hostname .. :tenant-name .. :asset-store ..} — who a request is and where
  its files live are inextricably linked, so one value carries both. A route may
  name a shared store via :store (the SPA shell's kaleidoscope.client, shared
  across tenants), which overrides the tenant's own :asset-store; compile
  middleware so it can read the route's :store."
  [resolve-fn]
  {:name    ::wrap-resolve-tenant
   :compile (fn [{:keys [store]} _opts]
              (fn [handler]
                (fn [request]
                  (let [tenant (resolve-fn request)]
                    (handler (assoc request :tenant (cond-> tenant
                                                      store (assoc :asset-store store))))))))})
