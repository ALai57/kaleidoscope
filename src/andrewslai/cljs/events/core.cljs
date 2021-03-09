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
 :update-user-profile!
 (fn [db [_ userinfo]]
   (assoc db :user-profile userinfo)))

(reg-event-db
 :modal
 (fn [db [_ data]]
   (assoc-in db [:modal] data)))

(reg-event-db
 :show-modal
 (fn [db [_ data]]
   (assoc-in db [:modal] {:show? true
                          :child data})))

(defn set-active-panel [db [_ value]]
  (merge db {:loading? true
             :active-panel value
             :active-content nil
             #_#_:recent-content nil}))
(reg-event-db
 :set-active-panel
 set-active-panel)
