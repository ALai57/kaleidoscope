(ns andrewslai.clj.api.auth
  (:require [andrewslai.clj.entities.oidc-id-token :as oidc]
            [buddy.auth.accessrules :as ar]))

(defn require-role
  [role {:keys [identity] :as request}]
  (if (contains? (oidc/get-realm-roles identity) role)
    true
    (ar/error (format "Unauthorized for role: %s (valid roles: %s)"
                      role
                      (oidc/get-realm-roles identity)))))
