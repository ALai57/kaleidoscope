(ns andrewslai.clj.api.authorization
  (:require [andrewslai.clj.api.authentication :as oidc]
            [buddy.auth.accessrules :as ar]
            [taoensso.timbre :as log]))

(defn require-role
  [role {:keys [identity uri] :as request}]
  (log/debugf "Checking if user %s has access to endpoint %s" identity uri)
  (if (contains? (oidc/get-realm-roles identity) role)
    true
    (ar/error (format "Unauthorized for role: %s (valid roles: %s)"
                      role
                      (oidc/get-realm-roles identity)))))
