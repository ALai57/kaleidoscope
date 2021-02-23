(ns andrewslai.clj.auth.keycloak
  (:require
   [andrewslai.clj.utils :as util]
   [buddy.auth.protocols :as proto]
   [buddy.auth.backends.token :as token]
   [cheshire.core :as json])
  (:import [org.keycloak.adapters BearerTokenRequestAuthenticator
            KeycloakDeploymentBuilder
            KeycloakDeployment]
           [org.keycloak.adapters.]))


;;import java client
(defn keycloak-validate []
  )


;;
(def keycloak-backend
  (token/token-backend
   {:token-name "Bearer"
    :authfn  (fn [request token])
    :unauthorized-handler (fn [])}))

(def adapter-config
  (new org.keycloak.representations.adapters.config.AdapterConfig))

(do (.setRealm adapter-config "test")
    (.setSslRequired adapter-config "external")
    (.setAuthServerUrl adapter-config "http://172.17.0.1:8080/auth/")
    (.setResource adapter-config "test-login-java")
    (.setCredentials adapter-config {"secret" "18c28e7a-3eb6-4726-b8c7-9c5d02f6bc88"})
    (.setConfidentialPort adapter-config 0))

(.isPublicClient adapter-config) ;; => false
(.getCredentials adapter-config) ;; => {:secret "18c28e7a-3eb6-4726-b8c7-9c5d02f6bc88"}
(.getRealm adapter-config) ;; => "test"
(.getSslRequired adapter-config) ;; => "external"
(.getAuthServerUrl adapter-config) ;; => "http://172.17.0.1:8080/auth/"
(.getResource adapter-config) ;; => "test-login-java"
(.getConfidentialPort adapter-config) ;; => 0
(type adapter-config)


(KeycloakDeploymentBuilder/build adapter-config)


(comment
  ;; Missing keycloak parent...
  (BearerTokenRequestAuthenticator. (KeycloakDeploymentBuilder/build adapter-config))
  )
