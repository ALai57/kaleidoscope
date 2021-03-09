(ns andrewslai.cljs.keycloak
  (:require [keycloak-js :as keycloak-js]
            [re-frame.core :refer [dispatch]]))

(goog-define AUTH_URL "defined-at-compile-time")
(goog-define CLIENTID "defined-at-compile-time")
(goog-define REALM "defined-at-compile-time")

(def HOST_URL
  (str js/window.location.protocol "//" js/window.location.host))

(defn keycloak
  [options]
  (js/Keycloak (clj->js options)))

(defn initialize!
  ([keycloak-instance]
   (initialize! keycloak-instance
                (fn [auth?] (js/console.log "Authenticated? " auth?))
                (fn [auth?] (js/console.log "Unable to initialize Keycloak"))))
  ([keycloak-instance success fail]
   (-> keycloak-instance
       (.init (clj->js {:checkLoginIframe false
                        :pkceMethod "S256"}))
       (.then success)
       (.catch fail))))

(defn login! [keycloak]
  (.login keycloak (clj->js {:scope "roles"
                             :prompt "consent"
                             :redirectUri HOST_URL})))

(defn logout! [keycloak]
  (.logout keycloak))

(defn account-management! [keycloak]
  (.accountManagement keycloak))

(defn load-profile!
  [keycloak]
  (-> keycloak
      .loadUserProfile
      (.then #(dispatch [:update-user-profile! (js->clj % :keywordize-keys true)]))))


;; Make sequence diagram for the authentication flow
;; Retrieve user ID and store in app db. Retreive all user params and store in reagent atom
