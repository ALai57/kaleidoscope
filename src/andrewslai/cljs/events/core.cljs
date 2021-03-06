(ns andrewslai.cljs.events.core
  (:require [andrewslai.cljs.db :refer [default-db]]
            [andrewslai.cljs.keycloak :as keycloak]
            [re-frame.core :refer [dispatch reg-event-db]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helper functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(reg-event-db
 :initialize-db
 (fn [_ _]
   default-db))

(reg-event-db
 :initialize-keycloak
 (fn [db [_ _]]
   (let [kc (:keycloak db)]
     (keycloak/initialize! kc
                           (fn on-success [auth?]
                             (js/console.log "Authenticated? " auth?)
                             (when auth? (set! js/parent.location.hash "/admin"))
                             (-> kc
                                 .loadUserProfile
                                 (.then #(dispatch [:update-user-profile! (js->clj % :keywordize-keys true)]))))
                           (fn on-error [e]
                             (js/console.log "Init error" e)))
     (js/console.log "***** Keycloak ****" kc)
     db)))

(reg-event-db
 :update-user-profile!
 (fn [db [_ userinfo]]
   (assoc db :user-profile userinfo)))

(reg-event-db
 :keycloak-login
 (fn [db _]
   ;; Does not need to return DB because this redirects
   (keycloak/login! (:keycloak db))))

(reg-event-db
 :keycloak-logout
 (fn [db _]
   ;; Does not need to return DB because this redirects
   (keycloak/logout! (:keycloak db))
   (dissoc db :user-profile)))

(reg-event-db
 :keycloak-account-management
 (fn [db _]
   ;; Does not need to return DB because this redirects
   (keycloak/account-management! (:keycloak db))))

(reg-event-db
 :modal
 (fn [db [_ data]]
   (assoc-in db [:modal] data)))

(reg-event-db
  :show-modal
  (fn [db [_ data]]
    (assoc-in db [:modal] {:show? true
                           :child data})))

(reg-event-db
  :process-http-response
  (fn [db [_ response processing-fn]]
    (processing-fn db response)))


(defn set-active-panel [db [_ value]]
  (merge db {:loading? true
             :active-panel value
             :active-content nil
             #_#_:recent-content nil}))
(reg-event-db
  :set-active-panel
  set-active-panel)
