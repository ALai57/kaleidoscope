(ns kaleidoscope.http-api.agents
  (:require [kaleidoscope.api.agents :as agents-api]
            [kaleidoscope.api.authentication :as oidc]
            [ring.util.http-response :refer [not-found ok]]))

(def reitit-agent-routes
  ["/agents"
   {:tags     ["agents"]
    :security [{:andrewslai-pkce ["roles" "profile"]}]}

   ["" {:get  {:summary "List agent definitions for the authenticated user (seeds defaults on first access)"
               :handler (fn [{:keys [components] :as request}]
                          (let [user-id (oidc/get-verified-email (:identity request))]
                            (ok (agents-api/get-agent-definitions (:database components) user-id))))}
         :post {:summary "Create a new custom agent definition"
                :handler (fn [{:keys [components body-params] :as request}]
                           (let [user-id (oidc/get-verified-email (:identity request))]
                             (ok (agents-api/create-agent-definition!
                                  (:database components)
                                  user-id
                                  body-params))))}}]

   ["/:definition-id"
    {:parameters {:path {:definition-id string?}}}

    ["" {:put {:summary "Update an agent's display-name, avatar, or system-prompt"
               :handler (fn [{:keys [components body-params path-params] :as request}]
                          (let [user-id       (oidc/get-verified-email (:identity request))
                                definition-id (parse-uuid (:definition-id path-params))]
                            (if-let [updated (agents-api/update-agent-definition!
                                              (:database components)
                                              user-id
                                              definition-id
                                              body-params)]
                              (ok updated)
                              (not-found {:reason "Agent definition not found"}))))}}]]])
