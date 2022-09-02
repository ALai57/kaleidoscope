(ns andrewslai.clj.http-api.auth.buddy-backends
  (:require [buddy.auth.backends.token :as token]
            [ring.util.http-response :refer [unauthorized]]))

(defn bearer-token-backend
  [authfn]
  (token/token-backend {:token-name           "Bearer"
                        :authfn               authfn
                        :unauthorized-handler unauthorized}))

(def unauthenticated-backend
  (bearer-token-backend (constantly nil)))

(defn authenticated-backend
  "Always returns an authenticated user. Useful for testing.

  ID token is a standard OIDC ID token
  https://openid.net/specs/openid-connect-core-1_0.html#IDToken"
  ([] (authenticated-backend true))
  ([id-token] (bearer-token-backend (constantly id-token))))
