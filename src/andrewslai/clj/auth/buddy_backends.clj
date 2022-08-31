(ns andrewslai.clj.auth.buddy-backends
  (:require [andrewslai.clj.utils.jwt :as jwt]
            [buddy.auth.accessrules :as ar]
            [buddy.auth.backends.token :as token]
            [buddy.auth.protocols :as proto]
            [buddy.core.codecs.base64 :as b64]
            [ring.util.http-response :refer [unauthorized]]
            [cheshire.core :as json]
            [taoensso.timbre :as log]
            [slingshot.slingshot :refer [try+ throw+]]))

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
