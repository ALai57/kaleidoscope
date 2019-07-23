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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helper functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn make-recent-articles-url [] "/get-recent-articles")
(defn make-article-url [article-type article-name]
  (str "/get-article/" (name article-type) "/" (name article-name)))

(defn modify-db [db mods]
  (reduce-kv #(assoc %1 %2 %3) db mods))

;; Dispatched when setting the active panel


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; db events for get-article
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(reg-event-db
 :retrieve-content
 (fn [db [_ article-type article-name]]

   (println "Retrieve-article path:"
            (make-article-url article-type article-name))

   (GET (make-article-url article-type article-name)
       {:handler #(dispatch [:process-response %1])
        :error-handler #(dispatch [:bad-response %1])})

   (modify-db db {:loading? true
                  :active-panel article-type
                  :active-content nil})))

(reg-event-db
 :process-response
 (fn [db [_ response]]
   (modify-db db {:loading? false
                  :active-content response})))

(reg-event-db
 :bad-response
 (fn [db [_ response]]
   (modify-db db {:loading? false
                  :active-content "Unable to load content"})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; db events for get-recent-articles
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(reg-event-db
 :set-active-panel
 (fn [db [_ value]]

   (GET (make-recent-articles-url)
       {:handler #(dispatch [:process-recent-response %1])
        :error-handler #(dispatch [:bad-recent-response %1])})

   (modify-db db {:loading? true
                  :active-panel value
                  :active-content nil
                  :recent-content nil})))

(reg-event-db
 :process-recent-response
 (fn [db [_ response]]
   ;;(println "SUCCESS Retreived recent articles: " response)
   (modify-db db {:loading? false
                  :recent-content response})))

(reg-event-db
 :bad-recent-response
 (fn [db [_ response]]
   (modify-db db {:loading? false
                  :recent-content "Unable to load content"})))
