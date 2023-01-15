(ns andrewslai.clj.http-api.auth.buddy-backends
  (:require [andrewslai.clj.http-api.auth.keycloak :as keycloak]
            [buddy.auth.backends.token :as token]
            [ring.util.http-response :refer [unauthorized]]
            [taoensso.timbre :as log]))

(defn bearer-token-backend
  [authfn]
  (token/token-backend {:token-name "Bearer"
                        :authfn     authfn}))

(def unauthenticated-backend
  (bearer-token-backend (fn authfn
                          [& _]
                          nil)))

(defn authenticated-backend
  "Always returns an authenticated user. Useful for testing.

  ID token is a standard OIDC ID token
  https://openid.net/specs/openid-connect-core-1_0.html#IDToken"
  ([] (authenticated-backend true))
  ([id-token]
   (log/debugf "Creating Authenticated backend with identity: %s" id-token)
   (bearer-token-backend (fn [& args]
                           (log/infof "[authenticated-backend]: Authenticated with user %s" id-token)
                           id-token))))

(defn keycloak-backend
  [keycloak-config-map]
  (-> keycloak-config-map
      (keycloak/make-keycloak-authenticator)
      (bearer-token-backend)))
