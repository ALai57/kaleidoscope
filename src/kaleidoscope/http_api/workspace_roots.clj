(ns kaleidoscope.http-api.workspace-roots
  (:require [kaleidoscope.http-api.http-utils :as hu]
            [kaleidoscope.persistence.workspace-roots :as persistence]
            [kaleidoscope.utils.local-files :as local-files]
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
                           (let [user-id (:user-id (:identity request))]
                             (ok (persistence/get-workspace-roots (:database components) user-id))))}

        :post {:summary   "Register a new workspace root"
               :responses (merge hu/openapi-401
                                  {200 {:description "The created workspace root"
                                        :content     {"application/json" {:schema [:any]}}}
                                   409 {:description "Path already registered"}})
               :handler   (fn [{:keys [components body-params] :as request}]
                            (let [user-id (:user-id (:identity request))
                                  path    (:path body-params)
                                  label   (:label body-params)]
                              (cond
                                (empty? path)
                                (bad-request {:reason "path is required"})

                                (not (local-files/path-allowed? path))
                                (bad-request {:reason "path is not under an allowed root"})

                                :else
                                (if-let [root (persistence/add-workspace-root!
                                               (:database components) user-id path label)]
                                  (ok root)
                                  {:status 409 :body {:reason "Path already registered"}}))))}}]

   ["/:workspace-root-id"
    {:parameters {:path {:workspace-root-id :uuid}}}

    ["" {:delete {:summary   "Remove a workspace root"
                  :responses (merge hu/openapi-401
                                     hu/openapi-404
                                     {204 {:description "Deleted"}})
                  :handler   (fn [{:keys [components parameters] :as request}]
                               (let [user-id (:user-id (:identity request))
                                     root-id (:workspace-root-id (:path parameters))]
                                 (if (persistence/delete-workspace-root!
                                      (:database components) root-id user-id)
                                   {:status 204}
                                   (not-found {:reason "Workspace root not found"}))))}}]]])
