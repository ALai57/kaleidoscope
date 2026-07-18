(ns kaleidoscope.http-api.agents
  (:require [kaleidoscope.api.agents :as agents-api]
            [kaleidoscope.http-api.http-utils :as hu]
            [kaleidoscope.persistence.tenant :as tenant]
            [ring.util.http-response :refer [not-found ok]]))

(def reitit-agent-routes
  ["/agents"
   {:tags     ["agents"]
    :security [{:andrewslai-pkce ["roles" "profile"]}]}

   ["" {:get  {:summary "List agent definitions for the authenticated user (seeds defaults on first access)"
               :handler (fn [{:keys [components] :as request}]
                          (let [user-id (:user-id (:identity request))
                                db      (tenant/scope (:database components) (hu/tenant-hostname request))]
                            (ok (agents-api/get-agent-definitions db user-id))))}
         :post {:summary "Create a new custom agent definition"
                :handler (fn [{:keys [components body-params] :as request}]
                           (let [user-id (:user-id (:identity request))
                                 db      (tenant/scope (:database components) (hu/tenant-hostname request))]
                             (ok (agents-api/create-agent-definition!
                                  db
                                  user-id
                                  body-params))))}}]

   ["/:definition-id"
    {:parameters {:path {:definition-id :uuid}}}

    ["" {:put {:summary "Update an agent's display-name, avatar, or system-prompt"
               :handler (fn [{:keys [components body-params parameters] :as request}]
                          (let [user-id       (:user-id (:identity request))
                                definition-id (:definition-id (:path parameters))
                                db            (tenant/scope (:database components) (hu/tenant-hostname request))]
                            (if-let [updated (agents-api/update-agent-definition!
                                              db
                                              user-id
                                              definition-id
                                              body-params)]
                              (ok updated)
                              (not-found {:reason "Agent definition not found"}))))}}]]])
