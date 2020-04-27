(ns andrewslai.cljs.events.articles
  (:require [ajax.core :refer [GET]]
            [andrewslai.cljs.events.core :refer [modify-db]]
            [re-frame.core :refer [dispatch reg-event-db]]))


(defn make-get-all-articles-url [] "/articles")
(defn make-article-url [article-name]
  (str "/articles/" (name article-name)))


(defn process-article-content [db response]
  (merge db {:loading? false
             :active-content response}))

(defn bad-article-content [db response]
  (merge db {:loading? false
             :active-content "Unable to load content"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; db events for get-article
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(reg-event-db
  :retrieve-content
  (fn [db [_ article-type article-name]]

    (println "Retrieving article:"
             (make-article-url article-name))

    (GET (make-article-url article-name)
        {:handler #(dispatch [:process-http-response %1 process-article-content])
         :error-handler #(dispatch [:process-http-response %1 bad-article-content])})

    (modify-db db {:loading? true
                   :active-panel article-type
                   :active-content nil})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; db events for get-recent-articles
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn process-recent-articles [db response]
  (merge db {:loading? false
             :recent-content response}))

(defn bad-recent-articles [db response]
  (merge db {:loading? false
             :recent-content "Unable to load content"}))

(reg-event-db
  :set-active-panel
  (fn [db [_ value]]

    (GET (make-get-all-articles-url)
        {:handler #(dispatch [:process-http-response %1 process-recent-articles])
         :error-handler #(dispatch [:process-http-response %1 bad-recent-articles])})

    (modify-db db {:loading? true
                   :active-panel value
                   :active-content nil
                   :recent-content nil})))
