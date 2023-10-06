(ns kaleidoscope.http-api.swagger
  (:require [compojure.api.middleware :as mw]
            [compojure.api.swagger :as swag]
            [compojure.api.sweet :refer [GET routes undocumented]]
            [kaleidoscope.utils.versioning :as v]
            [reitit.openapi :as openapi]
            [ring.swagger.common :as rsc]
            [ring.swagger.middleware :as rsm]
            [ring.swagger.swagger-ui :as swagger-ui]
            [ring.swagger.swagger2 :as swagger2]
            [reitit.swagger :as reitit-swagger]
            [reitit.swagger-ui :as reitit-swagger-ui]
            [ring.util.http-response :refer [ok found]]
            [spec-tools.core :as st-core]
            [spec-tools.swagger.core :as st]))

(def security-schemes
  "This works in conjunction with the `/resources/swagger-ui/index.html` to
  initialize the basic security scheme for PKCE. In addition to doing this, we
  also need to do some initialization in order to use PKCE.

  See https://swagger.io/docs/open-source-tools/swagger-ui/usage/oauth2/"
  {:andrewslai-pkce
   {:type  "oauth2"
    :flows {:authorizationCode {:authorizationUrl "https://keycloak.andrewslai.com/auth/realms/andrewslai/protocol/openid-connect/auth"
                                :tokenUrl         "https://keycloak.andrewslai.com/auth/realms/andrewslai/protocol/openid-connect/token"
                                :scopes           {"profile" "A users profile"
                                                   "roles"   "View users roles"}}}}})
#_{:type             "openIdConnect"
   :openIdConnectUrl "https://keycloak.andrewslai.com/auth/realms/andrewslai/.well-known/openid-configuration"}

(def reitit-openapi-routes
  ["" {:no-doc true}
   ["/openapi.json"
    {:get {:openapi {:info       {:title       "Kaleidoscope"
                                  :description "Kaleidoscope is a blogging app/content management system."
                                  :version     (:version (v/get-version-details))}
                     :components {:securitySchemes security-schemes}
                     :tags       [{:name        "articles"
                                   :description "Access and manage articles"}
                                  {:name        "photos"
                                   :description "Access user photos"}
                                  {:name        "groups"
                                   :description "Manage a user's groups"}
                                  {:name        "info"
                                   :description "Information about the server"}]}

           :handler (openapi/create-openapi-handler)}}]

   ["/api-docs"     {:get {:handler (fn [_request]
                                      (found "/api-docs/index.html"))}}]
   ["/api-docs/*"   {:get {:handler (reitit-swagger-ui/create-swagger-ui-handler
                                     {:url "/openapi.json"})}}]])
