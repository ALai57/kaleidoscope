(ns kaleidoscope.http-api.auth.auth0
  (:require [kaleidoscope.http-api.auth.jwt :as jwt]
            [steffan-westcott.clj-otel.api.trace.span :as span]
            [taoensso.timbre :as log])
  (:import (com.auth0.jwk JwkProviderBuilder)
           (com.auth0.jwt JWT)
           (com.auth0.jwt.algorithms Algorithm)
           (com.auth0.jwt.interfaces RSAKeyProvider)
           (java.util.concurrent TimeUnit)))

(defn- make-jwk-provider [domain]
  (-> (JwkProviderBuilder. (str "https://" domain "/"))
      (.cached 10 24 TimeUnit/HOURS)
      (.rateLimited 10 1 TimeUnit/MINUTES)
      (.build)))

(defn- rsa-key-provider [jwk-provider]
  (reify RSAKeyProvider
    (getPublicKeyById [_ kid]
      (.getPublicKey (.get jwk-provider kid)))
    (getPrivateKey [_] nil)
    (getPrivateKeyId [_] nil)))

(defn make-auth0-authenticator
  [{:keys [domain audience]}]
  (let [jwk-provider (make-jwk-provider domain)
        algorithm    (Algorithm/RSA256 (rsa-key-provider jwk-provider))
        verifier     (-> (JWT/require algorithm)
                         (.withIssuer (into-array String [(str "https://" domain "/")]))
                         (.withAudience (into-array String [audience]))
                         (.build))]
    (fn authfn [{:keys [request-id] :as _request} token]
      (span/with-span! {:name "kaleidoscope.authentication.auth0.verifier"}
        (try
          (log/infof "Validating Auth0 JWT token for request-id: %s" request-id)
          (when (.verify verifier token)
            (jwt/body token))
          (catch Exception e
            (log/warn "Auth0 authentication exception" (pr-str e))))))))
