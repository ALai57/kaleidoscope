(ns kaleidoscope.http-api.score-definitions
  (:require [kaleidoscope.api.score-definitions :as score-defs-api]
            [kaleidoscope.http-api.http-utils :as hu]
            [ring.util.http-response :refer [bad-request no-content not-found ok]]
            [taoensso.timbre :as log]))

;; Each dimension's :criteria text is spliced into the scoring prompt for
;; every run against this definition - capping count and length bounds
;; per-call token cost the same way score-project!'s definition-ids cap
;; bounds call *count*. is-default is deliberately not accepted here at
;; all: kaleidoscope.api.score-definitions/create-score-definition! forces
;; it to false regardless of what's in the body.
(def ScoreDimension
  [:map
   [:name [:string {:min 1 :max 200}]]
   [:criteria {:optional true} [:string {:max 2000}]]])

(def ScoreDefinitionRequest
  [:map
   [:name [:string {:min 1 :max 200}]]
   [:description {:optional true} [:string {:max 2000}]]
   [:scorer-type {:optional true} [:string {:max 100}]]
   [:dimensions {:optional true} [:vector {:max 20} ScoreDimension]]])

;; PUT is a partial update - every field optional, but dimensions/lengths
;; capped the same as on create, since update-score-definition! fully
;; replaces the dimensions vector when present.
(def ScoreDefinitionUpdateRequest
  [:map
   [:name {:optional true} [:string {:min 1 :max 200}]]
   [:description {:optional true} [:string {:max 2000}]]
   [:scorer-type {:optional true} [:string {:max 100}]]
   [:dimensions {:optional true} [:vector {:max 20} ScoreDimension]]])

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
                           (let [user-id (:user-id (:identity request))]
                             (ok (score-defs-api/get-score-definitions (:database components) user-id))))}

        :post {:summary    "Create a score definition"
               :responses  (merge hu/openapi-401
                                  {200 {:description "The created score definition"
                                        :content     {"application/json"
                                                      {:schema [:any]}}}})
               :parameters {:body ScoreDefinitionRequest}
               :handler   (fn [{:keys [components parameters] :as request}]
                            (let [user-id (:user-id (:identity request))]
                              (try
                                (ok (score-defs-api/create-score-definition!
                                     (:database components) user-id (:body parameters)))
                                (catch Exception e
                                  (log/errorf "Error creating score definition: %s" e)
                                  (bad-request {:error (.getMessage e)})))))}}]

   ["/:definition-id"
    {:parameters {:path {:definition-id :uuid}}}

    ["" {:get {:summary   "Get a score definition with its dimensions"
               :responses (merge hu/openapi-401
                                  hu/openapi-404
                                  {200 {:description "The score definition"
                                        :content     {"application/json"
                                                      {:schema [:any]}}}})
               :handler   (fn [{:keys [components parameters] :as request}]
                            (let [user-id (:user-id (:identity request))]
                              (if-let [defn (score-defs-api/get-score-definition
                                             (:database components)
                                             user-id
                                             (:definition-id (:path parameters)))]
                                (ok defn)
                                (not-found {:reason "Score definition not found"}))))}

         :put {:summary    "Update a score definition"
               :responses  (merge hu/openapi-401
                                  hu/openapi-404
                                  {200 {:description "The updated score definition"
                                        :content     {"application/json"
                                                      {:schema [:any]}}}})
               :parameters {:body ScoreDefinitionUpdateRequest}
               :handler   (fn [{:keys [components parameters] :as request}]
                            (let [user-id (:user-id (:identity request))
                                  def-id  (:definition-id (:path parameters))]
                              (if-let [result (score-defs-api/update-score-definition!
                                               (:database components) user-id def-id (:body parameters))]
                                (ok result)
                                (not-found {:reason "Score definition not found"}))))}

         :delete {:summary   "Delete a score definition (blocked if is_default)"
                  :responses (merge hu/openapi-401
                                     hu/openapi-404
                                     {204 {:description "Deleted successfully"}
                                      400 {:description "Cannot delete default definition"}})
                  :handler   (fn [{:keys [components parameters] :as request}]
                               (let [user-id (:user-id (:identity request))
                                     def-id  (:definition-id (:path parameters))
                                     result  (score-defs-api/delete-score-definition!
                                              (:database components) user-id def-id)]
                                 (cond
                                   (:error result) (case (:error result)
                                                     :cannot-delete-default
                                                     (bad-request {:error "Cannot delete a default score definition"})
                                                     :not-found
                                                     (not-found {:reason "Score definition not found"})
                                                     (bad-request {:error "Unknown error"}))
                                   :else (no-content))))}}]]])
