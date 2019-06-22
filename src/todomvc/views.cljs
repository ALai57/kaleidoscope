(ns todomvc.views
  (:require [reagent.core  :as reagent]
            [re-frame.core :refer [subscribe
                                   dispatch
                                   reg-sub]]
            [clojure.string :as str]
            ["react" :as react]
            ["react-spinners" :as spinner]
            ["emotion" :as emotion]
            [goog.object :as gobj]
            ["react-spinners/ClipLoader" :as cl-spinner]
            ))

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


(extend-protocol IPrintWithWriter
  js/Symbol
  (-pr-writer [sym writer _]
    (-write writer (str "\"" (.toString sym) "\""))))


(defn load-screen
  []
  (set! (.. cl-spinner -default -defaultProps -loading) false)
  (set! (.. cl-spinner -default -defaultProps -loading) true)
  (set! (.. cl-spinner -default -defaultProps -color) "#4286f4")
  (.render (.. (.-default cl-spinner) -prototype))
  )

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
             :data-analysis [data-analysis]
             :load-screen [load-screen]})

(defn app
  []
  (let [active-panel  (subscribe [:active-panel])]
    (fn []
      (get panels @active-panel))))

(comment
  (require '[goog.object :as gobj])
  (require '["react-spinners/ClipLoader" :as cl-spinner])
  (require '["react" :as react])

  (gobj/extend (.. (.-default cl-spinner) -prototype)
    js/React.Component.prototype)

  (extend-protocol IPrintWithWriter
    js/Symbol
    (-pr-writer [sym writer _]
      (-write writer (str "\"" (.toString sym) "\""))))

  (.render (.. (.-default cl-spinner) -prototype))
  (.render (.. (.-default cl-spinner) -prototype))
  (println "test Repl")
  (js-keys (.. (js-keys ) -prototype))
  (set! (.. cl-spinner -default -defaultProps -loading) false)
  (.. cl-spinner -default -defaultProps)
  )
