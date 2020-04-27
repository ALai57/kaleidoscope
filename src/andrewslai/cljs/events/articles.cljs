(ns andrewslai.cljs.events.articles
  (:require [ajax.core :refer [GET]]
            [andrewslai.cljs.events.core :refer [modify-db]]
            [re-frame.core :refer [dispatch reg-event-db]]))


(defn make-get-all-articles-url [] "/articles")
(defn make-article-url [article-name]
  (str "/articles/" (name article-name)))

(reg-event-db
  :process-response2
  (fn [db [_ response response->map]]
    (modify-db db (response->map response))))

(defn process-content [response]
  {:loading? false
   :active-content response})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; db events for get-article
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(reg-event-db
  :retrieve-content
  (fn [db [_ article-type article-name]]

    (println "Retrieving article:"
             (make-article-url article-name))

    (GET (make-article-url article-name)
        {:handler #(dispatch [:process-response %1 ])
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

    (GET (make-get-all-articles-url)
        {:handler #(dispatch [:process-recent-response %1])
         :error-handler #(dispatch [:bad-recent-response %1])})

    (modify-db db {:loading? true
                   :active-panel value
                   :active-content nil
                   :recent-content nil})))

(reg-event-db
  :process-recent-response
  (fn [db [_ response]]
    (modify-db db {:loading? false
                   :recent-content response})))

(reg-event-db
  :bad-recent-response
  (fn [db [_ response]]
    (modify-db db {:loading? false
                   :recent-content "Unable to load content"})))

