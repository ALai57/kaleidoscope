(ns andrewslai.clj.auth.buddy-backends
  (:require [andrewslai.clj.utils.jwt :as jwt]
            [buddy.auth.accessrules :as ar]
            [buddy.auth.backends.token :as token]
            [buddy.auth.protocols :as proto]
            [buddy.core.codecs.base64 :as b64]
            [cheshire.core :as json]
            [taoensso.timbre :as log]
            [slingshot.slingshot :refer [try+ throw+]]))

(defn- jwt-auth
  [jwt-claim-validator {:keys [request-id] :as request} token]
  (try
    (log/infof "Validating jwt token:\n %s" (-> {:header     (jwt/header token)
                                                 :body       (jwt/body token)
                                                 :request-id request-id}
                                                clojure.pprint/pprint
                                                with-out-str))
    (when (jwt-claim-validator token)
      (jwt/body token))
    (catch Exception e
      (log/error "Authentication exception" e))))

(defn oidc-backend
  [jwt-claim-validator]
  (token/token-backend {:token-name           "Bearer"
                        :authfn               (partial jwt-auth jwt-claim-validator)
                        :unauthorized-handler (fn [])}))

(defn unauthenticated-backend
  []
  (oidc-backend (fn [] (throw+ {:type :Unauthorized}))))

(defn authenticated-backend
  "Always returns an authenticated user. Useful for testing.

  ID token is a standard OIDC ID token
  https://openid.net/specs/openid-connect-core-1_0.html#IDToken"
  ([] (authenticated-backend true))
  ([id-token] (oidc-backend (constantly id-token))))
