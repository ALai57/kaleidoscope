(ns kaleidoscope.http-api.workspace-roots
  (:require [kaleidoscope.api.authentication :as oidc]
            [kaleidoscope.http-api.http-utils :as hu]
            [kaleidoscope.persistence.workspace-roots :as persistence]
            [ring.util.http-response :refer [bad-request not-found ok]]))

(def reitit-workspace-roots-routes
  ["/workspace-roots"
   {:tags     ["workspace-roots"]
    :security [{:andrewslai-pkce ["roles" "profile"]}]}

   ["" {:get {:summary   "List workspace roots for the authenticated user"
              :responses (merge hu/openapi-401
                                {200 {:description "A collection of workspace roots"
                                      :content     {"application/json" {:schema [:any]}}}})
              :handler   (fn [{:keys [components] :as request}]
                           (let [user-id (oidc/get-verified-email (:identity request))]
                             (ok (persistence/get-workspace-roots (:database components) user-id))))}

        :post {:summary   "Register a new workspace root"
               :responses (merge hu/openapi-401
                                  {200 {:description "The created workspace root"
                                        :content     {"application/json" {:schema [:any]}}}
                                   409 {:description "Path already registered"}})
               :handler   (fn [{:keys [components body-params] :as request}]
                            (let [user-id (oidc/get-verified-email (:identity request))
                                  path    (:path body-params)
                                  label   (:label body-params)]
                              (if (empty? path)
                                (bad-request {:reason "path is required"})
                                (if-let [root (persistence/add-workspace-root!
                                               (:database components) user-id path label)]
                                  (ok root)
                                  {:status 409 :body {:reason "Path already registered"}}))))}}]

   ["/:workspace-root-id"
    {:parameters {:path {:workspace-root-id string?}}}

    ["" {:delete {:summary   "Remove a workspace root"
                  :responses (merge hu/openapi-401
                                     hu/openapi-404
                                     {204 {:description "Deleted"}})
                  :handler   (fn [{:keys [components path-params] :as request}]
                               (let [user-id (oidc/get-verified-email (:identity request))
                                     root-id (parse-uuid (:workspace-root-id path-params))]
                                 (if (persistence/delete-workspace-root!
                                      (:database components) root-id user-id)
                                   {:status 204}
                                   (not-found {:reason "Workspace root not found"}))))}}]]])
