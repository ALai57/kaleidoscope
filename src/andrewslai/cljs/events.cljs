(ns andrewslai.cljs.events
  (:require
   [andrewslai.cljs.db :refer [default-db]]
   [re-frame.core :refer [reg-event-db
                          reg-event-fx
                          inject-cofx
                          path
                          after
                          dispatch]]
   [ajax.core :refer [GET]]))


;; Dispatched when setting the active panel
(reg-event-db
 :set-active-panel
 (fn [db [_ value]]

   (GET
       (str "/get-recent-content")
       {:handler #(dispatch [:process-recent-response %1])
        :error-handler #(dispatch [:bad-recent-response %1])})

   (-> db
       (assoc :loading? true)
       (assoc :active-panel value)
       (assoc :active-content nil)
       (assoc :recent-content nil))
   ))

(reg-event-db
 :retrieve-content
 (fn [db [_ content-type content-name]]

   (println "Retrieve-content path:"
            (str "/get-content/" (name content-type) "/" (name content-name)))

   (GET
       (str "/get-content/" (name content-type) "/" (name content-name))
       {:handler #(dispatch [:process-response %1])
        :error-handler #(dispatch [:bad-response %1])})

   (-> db
       (assoc :loading? true)
       (assoc :active-panel content-type)
       (assoc :active-content nil))))

(reg-event-db
 :process-response
 (fn
   [db [_ response]]
   (println "SUCCESS Retrieved content: " response)
   (-> db
       (assoc :loading? false) ;; take away that "Loading ..." UI
       (assoc :active-content response))))

(reg-event-db
 :bad-response
 (fn
   [db [_ response]]
   (-> db
       (assoc :loading? false)
       (assoc :active-content "Unable to load content"))))


(reg-event-db
 :process-recent-response
 (fn
   [db [_ response]]
   (println "SUCCESS Retreived recent articles: " response)
   (-> db
       (assoc :loading? false) ;; take away that "Loading ..." UI
       (assoc :recent-content response))))

(reg-event-db
 :bad-recent-response
 (fn
   [db [_ response]]
   (-> db
       (assoc :loading? false)
       (assoc :recent-content "Unable to load content"))))
