(ns kaleidoscope.api.authorization
  (:require [buddy.auth.accessrules :as ar]
            [clojure.string :as string]
            [taoensso.timbre :as log]))

(def public-access
  (constantly true))

(def ^:private authenticated-identity-types
  "Identity types that count as an authenticated caller. :unverified-user
  (a human session with no verified email) is deliberately excluded — every
  require-* function below rejects it regardless of roles."
  #{:verified-user :service-account})

(defn- authenticated-identity?
  [identity]
  (contains? authenticated-identity-types (:type identity)))

(defn- ephemeral-host?
  "Fly.io serves every deployed app at its own *.fly.dev hostname. Ephemeral
  test environments (plans/2026-07-03-ephemeral-fly-environment) are only
  ever reached this way, so the suffix alone is enough to recognize them
  without a domain registry."
  [server-name]
  (and server-name (string/ends-with? server-name ".fly.dev")))

(defn- role-domain
  "The role-string prefix to check for a given server-name. Every onboarded
  real domain gets its own namespace (e.g. `andrewslai.com`); every ephemeral
  Fly environment shares one `ephemeral` namespace instead of requiring a new
  Auth0 role per deploy. Data scoping (e.g. articles.hostname) is unaffected
  — it's derived independently via `http-utils/get-host`, not this fn."
  [server-name]
  (if (ephemeral-host? server-name)
    "ephemeral"
    server-name))

(defn require-role
  [role {:keys [identity uri] :as request}]
  (log/debugf "Checking if user %s has access to endpoint %s" identity uri)
  (cond
    (not (authenticated-identity? identity))
    (ar/error "Access requires an authenticated identity")

    (contains? (:roles identity) role)
    true

    :else
    (ar/error (format "Unauthorized for role: %s (valid roles: %s)"
                      role
                      (:roles identity)))))

(defn require-*-writer
  "Require the user to have the *-writer role, where * is the server-name (or
  `ephemeral` for any *.fly.dev host — see `role-domain`). For example, when
  sending a request to andrewslai.com, requires `andrewslai.com:writer`.
  Also requires a :verified-user or :service-account identity — unverified
  human sessions are rejected regardless of roles. Service-account (M2M)
  writes are attributed to the token's :sub (see `classify-identity`), so
  every route gated by this function must read :user-id off the identity map
  rather than deriving it from email."
  [{:keys [identity uri server-name] :as request}]
  (log/debugf "Checking if user %s has access to endpoint %s %s" identity server-name uri)
  (let [domain (role-domain server-name)
        role   (str domain ":writer")
        admin  (str domain ":admin")]
    (cond
      (not (authenticated-identity? identity))
      (ar/error "Write access requires an authenticated identity")

      (or (contains? (:roles identity) role)
          (contains? (:roles identity) admin))
      true

      :else
      (ar/error (format "Unauthorized for role: %s (valid roles: %s)"
                        role
                        (:roles identity))))))

(defn require-*-reader
  "Require the user to have the *-reader role, where * is the server-name (or
  `ephemeral` for any *.fly.dev host — see `role-domain`). For example, when
  sending a request to andrewslai.com, requires `andrewslai.com:reader`.
  Also requires a :verified-user or :service-account identity — unverified
  human sessions are rejected regardless of roles."
  [{:keys [identity uri server-name] :as request}]
  (log/debugf "Checking if user %s has access to endpoint %s" identity uri)
  (let [domain (role-domain server-name)
        role   (str domain ":reader")
        admin  (str domain ":admin")]
    (cond
      (not (authenticated-identity? identity))
      (ar/error "Read access requires an authenticated identity")

      (or (contains? (:roles identity) role)
          (contains? (:roles identity) admin))
      true

      :else
      (ar/error (format "Unauthorized for role: %s (valid roles: %s)"
                        role
                        (:roles identity))))))

(defn require-*-admin
  "Require the user to have the *-admin role, where * is the server-name (or
  `ephemeral` for any *.fly.dev host — see `role-domain`). For example, when
  sending a request to andrewslai.com, requires `andrewslai.com:admin`. Also
  requires a :verified-user or :service-account identity — unverified human
  sessions are rejected regardless of roles."
  [{:keys [identity uri server-name] :as request}]
  (log/debugf "Checking if user %s has access to endpoint %s on domain %s" identity uri server-name)
  (let [role (str (role-domain server-name) ":admin")]
    (cond
      (not (authenticated-identity? identity))
      (ar/error "Admin access requires an authenticated identity")

      (contains? (:roles identity) role)
      true

      :else
      (ar/error (format "Unauthorized for role: %s (valid roles: %s)"
                        role
                        (:roles identity))))))
