(ns andrewslai.cljs.events.core
  (:require [andrewslai.cljs.db :refer [default-db]]
            [andrewslai.cljs.keycloak :as keycloak]
            [re-frame.core :refer [reg-event-db]]))

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
   (keycloak/initialize! (:keycloak db)
                         (fn on-success [auth?]
                           (js/console.log "Authenticated? " auth?)
                           (when auth? (set! js/parent.location.hash "/home")))
                         (fn on-error [e]
                           (js/console.log "Init error" e)))
   (js/console.log "***** Keycloak ****" (:keycloak db))
   db))

(reg-event-db
 :keycloak-login
 (fn [db _]
   (keycloak/login! (:keycloak db))))

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
