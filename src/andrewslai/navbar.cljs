(ns andrewslai.navbar
  (:require [re-frame.core :refer [dispatch]]
            [clojure.string :as str]))

(def nav-images-path "images/nav-bar/")

(defn- nav-icon
  [route img]
  [:a.zoom-icon {:href (str "#/" route)
                 :style {:width "auto"}}
   [:img.navbutton
    {:src (str nav-images-path img)
     :on-click #(dispatch [:set-active-panel (keyword route)])}]])

(defn primary-nav
  []
  [:div#primary-nav
   [:a.zoom-icon {:href "#/home"
                  :style {:float "left"
                          :display "inline-block"
                          :width "auto"}}
    [:img.navbutton {:src "images/nav-bar/favicon-white.svg"
                     :on-click #(dispatch [:set-active-panel :home])}]]
   [:div#secondary-nav
    [nav-icon "thoughts" "andrew-head-icon.svg"]
    [nav-icon "archive" "archive-icon.svg"]
    [nav-icon "about" "andrew-silhouette-icon.svg"]
    [nav-icon "research" "neuron-icon.svg"]
    [nav-icon "data-analysis" "statistics-icon.svg"]]])
