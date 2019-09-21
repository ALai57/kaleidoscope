(ns andrewslai.cljs.views
  (:require [andrewslai.cljs.article :as article]
            [andrewslai.cljs.article-cards :as cards]
            [andrewslai.cljs.circle-nav :as circle-nav]
            [andrewslai.cljs.loading :as loading]
            [andrewslai.cljs.pose :as pose]
            [andrewslai.cljs.navbar :as nav]

            [andrewslai.cljs.pages.home :refer [home]]
            [andrewslai.cljs.d3 :refer [d3-example]]
            [re-frame.core :refer [subscribe]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Landing pages
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn thoughts
  []
  [:div
   [nav/primary-nav]
   [:p "Thoughts"]
   [:div#primary-content
    [article/primary-content]]
   [:div#rcb
    [cards/recent-content-display "thoughts"]]
   [loading/load-screen]])

(defn archive
  []
  [:div
   [nav/primary-nav]
   [:p "Archive"]
   [:div#primary-content
    [article/primary-content]]
   [:div#rcb
    [cards/recent-content-display "archive"]]
   [loading/load-screen]])

(defn about
  []
  [:div
   [nav/primary-nav]
   [:p "About"]
   [:div#primary-content
    [article/primary-content]]
   [:div#rcb
    [cards/recent-content-display "about"]]
   [loading/load-screen]])

(defn research
  []
  [:div
   [nav/primary-nav]
   [:p "Research"]
   [:div#primary-content
    [article/primary-content]]
   [:div#rcb
    [cards/recent-content-display "research"]]
   [loading/load-screen]])

(defn data-analysis
  []
  [:div
   [nav/primary-nav]
   [:p "Data Analysis"]
   [:div#primary-content
    [article/primary-content]]
   [:div#rcb
    [cards/recent-content-display "data-analysis"]]
   [loading/load-screen]])

(defn load-page
  []
  [:div
   [nav/primary-nav]
   [:p "Test loading page"]
   [:div#primary-content
    [article/primary-content]]
   [:div#rcb
    [cards/recent-content-display "thoughts"]]
   [loading/load-screen-test]])

(defn pose
  []
  [:div
   [nav/primary-nav]
   [:p "Testing poses"]
   [:div#primary-content
    [article/primary-content]]
   [:div#rcb
    [cards/recent-content-display "thoughts"]]
   [pose/pose]])

(def panels {:home [home]
             :thoughts [thoughts]
             :archive [archive]
             :about [about]
             :research [research]
             :data-analysis [data-analysis]
             :load-screen [load-page]
             :d3-example [d3-example]
             :pose [pose]})

(defn app
  []
  (let [active-panel (subscribe [:active-panel])]
    (fn []
      (get panels @active-panel))))
