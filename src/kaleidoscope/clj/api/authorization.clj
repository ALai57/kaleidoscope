(ns kaleidoscope.clj.api.authorization
  (:require [kaleidoscope.clj.api.authentication :as oidc]
            [buddy.auth.accessrules :as ar]
            [taoensso.timbre :as log]))

(def public-access
  (constantly true))

(defn require-role
  [role {:keys [identity uri] :as request}]
  (log/debugf "Checking if user %s has access to endpoint %s" identity uri)
  (if (contains? (oidc/get-realm-roles identity) role)
    true
    (ar/error (format "Unauthorized for role: %s (valid roles: %s)"
                      role
                      (oidc/get-realm-roles identity)))))

(defn require-*-writer
  "The route requires the user to have the *-writer role, where
  * is the server-name. For example, when sending a request to
  andrewslai.com, requires `andrewslai.com-writer`"
  [{:keys [identity uri server-name] :as request}]
  (log/debugf "Checking if user %s has access to endpoint %s" identity uri)
  (let [role  (str server-name ":writer")
        admin (str server-name ":admin")]
    (if (or (contains? (oidc/get-realm-roles identity) role)
            (contains? (oidc/get-realm-roles identity) admin))
      true
      (ar/error (format "Unauthorized for role: %s (valid roles: %s)"
                        role
                        (oidc/get-realm-roles identity))))))

(defn require-*-reader
  "The route requires the user to have the *-reader role, where
  * is the server-name. For example, when sending a request to
  andrewslai.com, requires `andrewslai.com-reader`"
  [{:keys [identity uri server-name] :as request}]
  (log/debugf "Checking if user %s has access to endpoint %s" identity uri)
  (let [role  (str server-name ":reader")
        admin (str server-name ":admin")]
    (if (or (contains? (oidc/get-realm-roles identity) role)
            (contains? (oidc/get-realm-roles identity) admin))
      true
      (ar/error (format "Unauthorized for role: %s (valid roles: %s)"
                        role
                        (oidc/get-realm-roles identity))))))

(defn require-*-admin
  "The route requires the user to have the *-reader role, where
  * is the server-name. For example, when sending a request to
  andrewslai.com, requires `andrewslai.com-reader`"
  [{:keys [identity uri server-name] :as request}]
  (log/debugf "Checking if user %s has access to endpoint %s" identity uri)
  (let [role (str server-name ":admin")]
    (if (contains? (oidc/get-realm-roles identity) role)
      true
      (ar/error (format "Unauthorized for role: %s (valid roles: %s)"
                        role
                        (oidc/get-realm-roles identity))))))
