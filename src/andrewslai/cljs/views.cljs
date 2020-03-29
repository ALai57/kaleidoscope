(ns andrewslai.cljs.views
  (:require [andrewslai.cljs.article :as article]
            [andrewslai.cljs.article-cards :as cards]
            [andrewslai.cljs.navbar :as nav]
            [andrewslai.cljs.pages.home :refer [home]]
            [re-frame.core :refer [subscribe]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Landing pages
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn thoughts []
  [:div
   [nav/primary-nav]
   [:p "Thoughts"]
   [:div#primary-content
    [article/primary-content]]
   [:div#rcb
    [cards/recent-content-display "thoughts"]]])

(defn archive []
  [:div
   [nav/primary-nav]
   [:p "Archive"]
   [:div#primary-content
    [article/primary-content]]
   [:div#rcb
    ]])

(defn about []
  [:div {:onClick (fn [& x] (println "about"))
         :style {:height "100%"
                 :width "100%"
                 :position "absolute"}} 
   [nav/primary-nav]
   [:div {:style {:height "100%"}}
    [cards/recent-content-display "about"]]])

(defn research []
  [:div
   [nav/primary-nav]
   [:p "Research"]
   [:div#primary-content
    [article/primary-content]]
   [:div#rcb
    [cards/recent-content-display "research"]]])

(defn data-analysis []
  [:div
   [nav/primary-nav]
   [:p "Data Analysis"]
   [:div#primary-content
    [article/primary-content]]
   [:div#rcb
    [cards/recent-content-display "data-analysis"]]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test pages
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(def panels {:home [home]
             :thoughts [thoughts]
             :archive [archive]
             :about [about]
             :research [research]
             :data-analysis [data-analysis]
             })

(defn app []
  (let [active-panel (subscribe [:active-panel])]
    (fn []
      (println "active panel" active-panel)
      (get panels @active-panel))))
