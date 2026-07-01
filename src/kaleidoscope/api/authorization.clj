(ns kaleidoscope.api.authorization
  (:require [buddy.auth.accessrules :as ar]
            [taoensso.timbre :as log]))

(def public-access
  (constantly true))

(defn require-role
  [role {:keys [identity uri] :as request}]
  (log/debugf "Checking if user %s has access to endpoint %s" identity uri)
  (if (contains? (:roles identity) role)
    true
    (ar/error (format "Unauthorized for role: %s (valid roles: %s)"
                      role
                      (:roles identity)))))

(defn require-*-writer
  "Require the user to have the *-writer role, where * is the server-name. For
  example, when sending a request to andrewslai.com, requires
  `andrewslai.com-writer`. Also requires a :verified-user identity — M2M/service
  tokens are rejected regardless of roles."
  [{:keys [identity uri server-name] :as request}]
  (log/debugf "Checking if user %s has access to endpoint %s %s" identity server-name uri)
  (let [role  (str server-name ":writer")
        admin (str server-name ":admin")]
    (cond
      (not= :verified-user (:type identity))
      (ar/error "Write access requires a verified user identity")

      (or (contains? (:roles identity) role)
          (contains? (:roles identity) admin))
      true

      :else
      (ar/error (format "Unauthorized for role: %s (valid roles: %s)"
                        role
                        (:roles identity))))))

(defn require-*-reader
  "Require the user to have the *-reader role, where * is the server-name. For
  example, when sending a request to andrewslai.com, requires
  `andrewslai.com-reader`"
  [{:keys [identity uri server-name] :as request}]
  (log/debugf "Checking if user %s has access to endpoint %s" identity uri)
  (let [role  (str server-name ":reader")
        admin (str server-name ":admin")]
    (if (or (contains? (:roles identity) role)
            (contains? (:roles identity) admin))
      true
      (ar/error (format "Unauthorized for role: %s (valid roles: %s)"
                        role
                        (:roles identity))))))

(defn require-*-admin
  "Require the user to have the *-admin role, where * is the server-name. For
  example, when sending a request to andrewslai.com, requires
  `andrewslai.com-admin`"
  [{:keys [identity uri server-name] :as request}]
  (log/debugf "Checking if user %s has access to endpoint %s on domain %s" identity uri server-name)
  (let [role (str server-name ":admin")]
    (if (contains? (:roles identity) role)
      true
      (ar/error (format "Unauthorized for role: %s (valid roles: %s)"
                        role
                        (:roles identity))))))
