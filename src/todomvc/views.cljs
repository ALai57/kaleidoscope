(ns todomvc.views
  (:require [reagent.core  :as reagent]
            [re-frame.core :refer [subscribe
                                   dispatch
                                   reg-sub]]
            [clojure.string :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; My website
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn nav-icon
  [route img]
  [:a {:href (str "#/" route)
       :class "zoom-icon"
       :style {:width "auto"}}
   [:img {:src (str "images/nav-bar/" img)
          :class "navbutton"
          :on-click #(dispatch [:set-active-panel (keyword route)])}]])

(defn primary-nav
  []
  [:div#primary-nav
   [:a {:href "#/home"
        :class "zoom-icon"
        :style {:float "left"
                :display "inline-block"
                :width "auto"}}
    [:img {:src "images/nav-bar/favicon-white.svg"
           :class "navbutton"
           :on-click #(dispatch [:set-active-panel :home])}]]
   [:div#secondary-nav
    [nav-icon "thoughts" "andrew-head-icon.svg"]
    [nav-icon "archive" "archive-icon.svg"]
    [nav-icon "about" "andrew-silhouette-icon.svg"]
    [nav-icon "research" "neuron-icon.svg"]
    [nav-icon "data-analysis" "statistics-icon.svg"]]])

(defn primary-content
  []
  (let [active-content (subscribe [:active-content])]
    (println "Got active content! " @active-content)
    [:p (str @active-content)]))

(defn home
  []
  [:div
   [primary-nav]
   [primary-content]])

(defn thoughts
  []
  [:div
   [primary-nav]
   [:p "Thoughts"]
   [primary-content]])

(defn archive
  []
  [:div
   [primary-nav]
   [:p "Archive"]
   [primary-content]])

(defn about
  []
  [:div
   [primary-nav]
   [:p "About"]
   [primary-content]])

(defn research
  []
  [:div
   [primary-nav]
   [:p "Research"]
   [primary-content]])

(defn data-analysis
  []
  [:div
   [primary-nav]
   [:p "Data Analysis"]
   [primary-content]])

(def panels {:home [home]
             :thoughts [thoughts]
             :archive [archive]
             :about [about]
             :research [research]
             :data-analysis [data-analysis]})

(defn app
  []
  (let [active-panel  (subscribe [:active-panel])]
    (fn []
      (get panels @active-panel))))
