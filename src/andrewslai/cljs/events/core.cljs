(ns andrewslai.cljs.events.core
  (:require [andrewslai.cljs.db :refer [default-db]]
            [re-frame.core :refer [reg-event-db]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helper functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn modify-db [db mods]
  (reduce-kv #(assoc %1 %2 %3) db mods))

(reg-event-db
  :initialize-db
  (fn [_ _]
    default-db))

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
