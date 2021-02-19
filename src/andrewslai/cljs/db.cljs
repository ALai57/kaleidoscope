(ns andrewslai.cljs.db
  (:require [cljs.spec.alpha :as s]
            [andrewslai.cljs.keycloak :as keycloak]))


;; -- Spec --------------------------------------------------------------------
;; The value in app-db should always match this spec.

(s/def ::active-panel keyword?)
(s/def ::loading boolean?)
(s/def ::db
  (s/keys :req-un [::active-panel
                   ::active-content
                   ::loading?]))

;; -- Default app-db Value  ---------------------------------------------------

(def default-db
  {:active-panel             :home
   :active-content           nil
   :resume-info              nil
   :selected-resume-category nil
   :selected-resume-card     nil
   :recent-content           nil
   :loading?                 false
   :loading-resume?          false
   :editor-data              nil
   :editor-metadata          {}
   :keycloak                 (keycloak/keycloak {:url      keycloak/AUTH_URL
                                                 :realm    keycloak/REALM
                                                 :clientId keycloak/CLIENTID})})
