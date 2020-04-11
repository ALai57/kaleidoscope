(ns andrewslai.cljs.navbar
  (:require [re-frame.core :refer [dispatch subscribe]]))

(def nav-images-path "images/nav-bar/")

(defn- nav-icon
  [route img]
  [:a.zoom-icon {:href (str "#/" route)}
   [:img.navbutton
    {:src (str nav-images-path img)
     :on-click #(dispatch [:set-active-panel (keyword route)])}]])

(defn- login-icon
  [avatar]
  [:a.zoom-icon {:href (str "#/profile")}
   [:img.navbutton
    {:src (or avatar "/images/nav-bar/unknown-user.svg")
     :on-click #(dispatch [:set-active-panel :admin])}]])

(defn primary-nav []
  (let [avatar (subscribe [:avatar])]
    (println @avatar)
    [:div#primary-nav
     [:a.zoom-icon {:href "#/home"
                    :style {:float "left"}}
      [:img.navbutton {:src "images/nav-bar/favicon-white.svg"
                       :on-click #(dispatch [:set-active-panel :home])}]]
     [:div#secondary-nav
      [login-icon @avatar]
      [nav-icon "thoughts" "andrew-head-icon.svg"]
      [nav-icon "archive" "archive-icon.svg"]
      [nav-icon "about" "andrew-silhouette-icon.svg"]
      [nav-icon "research" "neuron-icon.svg"]
      [nav-icon "data-analysis" "statistics-icon.svg"]]]))
