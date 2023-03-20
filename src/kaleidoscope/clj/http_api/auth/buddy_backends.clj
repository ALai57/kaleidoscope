(ns kaleidoscope.clj.http-api.auth.buddy-backends
  (:require [buddy.auth.backends.token :as token]
            [clojure.string :as string]
            [kaleidoscope.clj.http-api.auth.keycloak :as keycloak]
            [ring.util.http-response :refer [unauthorized]]
            [steffan-westcott.clj-otel.api.trace.span :as span]
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
   (bearer-token-backend (fn [request bearer-token]
                           (log/infof "[authenticated-backend]: Authenticated with user %s" id-token)
                           ;; For testing purposes allow us to change the user
                           ;; identity by putting in a specific string.
                           (span/with-span! {:name "kaleidoscope.authentication.token-backend"}
                             (if (string/starts-with? bearer-token "user ")
                               (let [new-identity (second (string/split bearer-token #" "))]
                                 (log/debugf "Overriding user identity with `%s`" new-identity)
                                 (assoc id-token :sub new-identity))
                               id-token))))))

(defn keycloak-backend
  [keycloak-config-map]
  (-> keycloak-config-map
      (keycloak/make-keycloak-authenticator)
      (bearer-token-backend)))
