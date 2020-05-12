(ns andrewslai.cljs.events.articles
  (:require [ajax.core :refer [GET]]
            [re-frame.core :refer [dispatch reg-event-db]]))

(defn load-article [db [_ response]]
  (merge db {:loading? false
             :active-content response}))
(reg-event-db
  :load-article
  load-article)


(defn load-recent-articles [db [_ response]]
  (merge db {:loading? false
             :recent-content response}))
(reg-event-db
  :load-recent-articles
  load-recent-articles)

(defn set-active-panel [db [_ value]]
  (merge db {:loading? true
             :active-panel value
             :active-content nil
             #_#_:recent-content nil}))
(reg-event-db
  :set-active-panel
  set-active-panel)
