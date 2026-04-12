(ns kaleidoscope.http-api.score-definitions
  (:require [kaleidoscope.api.authentication :as oidc]
            [kaleidoscope.api.score-definitions :as score-defs-api]
            [kaleidoscope.http-api.http-utils :as hu]
            [ring.util.http-response :refer [bad-request no-content not-found ok]]
            [taoensso.timbre :as log]))

(def reitit-score-definition-routes
  ["/score-definitions"
   {:tags     ["score-definitions"]
    :security [{:andrewslai-pkce ["roles" "profile"]}]}

   ["" {:get {:summary   "List all score definitions for the authenticated user"
              :responses (merge hu/openapi-401
                                {200 {:description "A collection of score definitions"
                                      :content     {"application/json"
                                                    {:schema [:any]}}}})
              :handler   (fn [{:keys [components] :as request}]
                           (let [user-id (oidc/get-verified-email (:identity request))]
                             (ok (score-defs-api/get-score-definitions (:database components) user-id))))}

        :post {:summary   "Create a score definition"
               :responses (merge hu/openapi-401
                                  {200 {:description "The created score definition"
                                        :content     {"application/json"
                                                      {:schema [:any]}}}})
               :handler   (fn [{:keys [components body-params] :as request}]
                            (let [user-id (oidc/get-verified-email (:identity request))]
                              (try
                                (ok (score-defs-api/create-score-definition!
                                     (:database components) user-id body-params))
                                (catch Exception e
                                  (log/errorf "Error creating score definition: %s" e)
                                  (bad-request {:error (.getMessage e)})))))}}]

   ["/:definition-id"
    {:parameters {:path {:definition-id string?}}}

    ["" {:get {:summary   "Get a score definition with its dimensions"
               :responses (merge hu/openapi-401
                                  hu/openapi-404
                                  {200 {:description "The score definition"
                                        :content     {"application/json"
                                                      {:schema [:any]}}}})
               :handler   (fn [{:keys [components path-params] :as _request}]
                            (if-let [defn (score-defs-api/get-score-definition
                                           (:database components)
                                           (parse-uuid (:definition-id path-params)))]
                              (ok defn)
                              (not-found {:reason "Score definition not found"})))}

         :put {:summary   "Update a score definition"
               :responses (merge hu/openapi-401
                                  hu/openapi-404
                                  {200 {:description "The updated score definition"
                                        :content     {"application/json"
                                                      {:schema [:any]}}}})
               :handler   (fn [{:keys [components body-params path-params] :as _request}]
                            (let [def-id (parse-uuid (:definition-id path-params))]
                              (if-let [result (score-defs-api/update-score-definition!
                                               (:database components) def-id body-params)]
                                (ok result)
                                (not-found {:reason "Score definition not found"}))))}

         :delete {:summary   "Delete a score definition (blocked if is_default)"
                  :responses (merge hu/openapi-401
                                     hu/openapi-404
                                     {204 {:description "Deleted successfully"}
                                      400 {:description "Cannot delete default definition"}})
                  :handler   (fn [{:keys [components path-params] :as _request}]
                               (let [def-id (parse-uuid (:definition-id path-params))
                                     result (score-defs-api/delete-score-definition!
                                             (:database components) def-id)]
                                 (cond
                                   (:error result) (case (:error result)
                                                     :cannot-delete-default
                                                     (bad-request {:error "Cannot delete a default score definition"})
                                                     :not-found
                                                     (not-found {:reason "Score definition not found"})
                                                     (bad-request {:error "Unknown error"}))
                                   :else (no-content))))}}]]])
