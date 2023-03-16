(ns kaleidoscope.clj.http-api.swagger
  (:require [compojure.api.middleware :as mw]
            [compojure.api.swagger :as swag]
            [compojure.api.sweet :refer [GET routes undocumented]]
            [ring.swagger.common :as rsc]
            [ring.swagger.middleware :as rsm]
            [ring.swagger.swagger-ui :as swagger-ui]
            [ring.swagger.swagger2 :as swagger2]
            [ring.util.http-response :refer [ok]]
            [spec-tools.core :as st-core]
            [spec-tools.swagger.core :as st]))

(def example-data-2
  {:andrewslai.article/article {:summary "An example article"
                                :value   {:id           10
                                          :article_tags "thoughts"
                                          :article_url  "my-test-article"
                                          :author       "Andrew Lai"
                                          :content      "<h1>Hello world!</h1>"
                                          :timestamp    "2020-10-28T00:00:00"
                                          :title        "My test article"}}})

(defn extract-specs [swagger]
  (reduce (fn [acc [_ {{schemas :schemas} :components :as x}]]
            (if schemas
              (conj acc schemas)
              acc))
          {}
          (mapcat second (:paths swagger))))

(defn specs->components [swagger-specs]
  (reduce-kv (fn [acc k v]
               (assoc acc
                      k (-> v st-core/create-spec st/transform)))
             {}
             swagger-specs))

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

(def swagger-ui-routes
  (routes
    (undocumented
     (swagger-ui/swagger-ui {:path         "/swagger"
                             :swagger-docs "/swagger.json"}))
    (GET "/swagger.json" req
      {:summary  "Return a swagger 3.0.2 spec"
       :produces #{"application/json"}}
      (let [runtime-info1 (mw/get-swagger-data req)
            runtime-info2 (rsm/get-swagger-data req)
            base-path     {:basePath (swag/base-path req)}
            options       (:compojure.api.request/ring-swagger req)
            paths         (:compojure.api.request/paths req)
            swagger       (apply rsc/deep-merge
                                 (keep identity [base-path
                                                 paths
                                                 runtime-info1
                                                 runtime-info2]))
            spec          (st/swagger-spec
                           (swagger2/swagger-json swagger options))]
        (-> spec
            (merge {:openapi    "3.0.2"
                    :info       {:title       "andrewslai"
                                 :description "The backend HTTP API for a blog"}
                    :tags       [{:name        "articles"
                                  :description "Articles (published and non-published)"}]
                    :components {:schemas         (-> swagger
                                                      extract-specs
                                                      specs->components)
                                 :securitySchemes security-schemes

                                 :examples (reduce-kv (fn [acc k v]
                                                        (assoc acc (name k) v))
                                                      {}
                                                      example-data-2)}})
            (dissoc :swagger)
            ok)))))

(def example-wedding-data
  {:andrewslai.albums/album {:summary "An example album"
                             :value   {:id             1
                                       :album-name     "my album"
                                       :created-at     "2022-10-01T02:55:27Z"
                                       :modified-at    "2022-10-01T02:55:27Z"}}})

(def swagger-wedding-routes
  (routes
    (undocumented
     (swagger-ui/swagger-ui {:path         "/swagger"
                             :swagger-docs "/swagger.json"}))
    (GET "/swagger.json" req
      {:summary  "Return a swagger 3.0.2 spec"
       :produces #{"application/json"}}
      (let [runtime-info1 (mw/get-swagger-data req)
            runtime-info2 (rsm/get-swagger-data req)
            base-path     {:basePath (swag/base-path req)}
            options       (:compojure.api.request/ring-swagger req)
            paths         (:compojure.api.request/paths req)
            swagger       (apply rsc/deep-merge
                                 (keep identity [base-path
                                                 paths
                                                 runtime-info1
                                                 runtime-info2]))
            spec          (st/swagger-spec
                           (swagger2/swagger-json swagger options))]
        (-> spec
            (assoc :openapi    "3.0.2"
                   :info       {:title       "caheriaguilar.and.andrewslai"
                                :description "The backend HTTP API for a photo sharing web-app"}
                   :components {:schemas  (-> swagger
                                              extract-specs
                                              specs->components)
                                :examples (reduce-kv (fn [acc k v]
                                                       (assoc acc (name k) v))
                                                     {}
                                                     example-wedding-data)})
            (dissoc :swagger)
            ok)))))
