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
  :process-http-response
  (fn [db [_ response processing-fn]]
    (processing-fn db response)))
