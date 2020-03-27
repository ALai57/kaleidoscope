(ns andrewslai.cljs.views
  (:require [andrewslai.cljs.article :as article]
            [andrewslai.cljs.article-cards :as cards]
            [andrewslai.cljs.loading :as loading]
            [andrewslai.cljs.pose :as pose]
            [andrewslai.cljs.navbar :as nav]
            [andrewslai.cljs.resume-cards :as resume-cards]

            [andrewslai.cljs.pages.home :refer [home]]
            [re-frame.core :refer [subscribe
                                   dispatch]]))

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

(defn reset-resume-info [x]
  (let [clicked-element (.-target x)
        clicked-class (.-className clicked-element)]
    (when-not (or (clojure.string/includes? clicked-class "resume-info-image")
                  (clojure.string/includes? clicked-class "resume-info-icon")
                  (clojure.string/includes? clicked-class "card-description")
                  (clojure.string/includes? clicked-class "card-title")
                  (clojure.string/includes? clicked-class "card-text"))
      (dispatch [:reset-resume-info]))))

(defn about
  []
  [:div {:onClick reset-resume-info
         :style {:height "100%"
                 :width "100%"
                 :position "absolute"}} 
   [nav/primary-nav]
   [:div {:style {:height "100%"}}
    [resume-cards/me]]
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test pages
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
             :pose [pose]})

(defn app
  []
  (let [active-panel (subscribe [:active-panel])]
    (fn []
      (println "active panel" active-panel)
      (get panels @active-panel))))
