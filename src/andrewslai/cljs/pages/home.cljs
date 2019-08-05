(ns andrewslai.cljs.pages.home
  (:require [andrewslai.cljs.article :as article]
            [andrewslai.cljs.article-cards :as cards]
            [andrewslai.cljs.circle-nav :as circle-nav]
            [andrewslai.cljs.loading :as loading]
            [andrewslai.cljs.navbar :as nav]
            [reagent.core  :as reagent]
            [re-frame.core :refer [subscribe
                                   dispatch
                                   reg-sub]]
            [clojure.string :as str]

            [cljsjs.react-bootstrap]
            ["react" :as react]
            ["react-spinners" :as spinner]
            ["emotion" :as emotion]
            [goog.object :as gobj]
            ["react-spinners/ClipLoader" :as cl-spinner]
            [reframe-components.recom-radial-menu :as rcm]
            [stylefy.core :refer [init]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Init and settings
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(init)
(def icon-list ["images/teamwork.svg"
                "images/github.svg"
                "images/cv.svg"
                "images/tango-image-ccby.svg"
                "images/volunteer.svg"
                "images/clojure-logo.svg"
                "images/my-silhouette.svg"])

(def center-icon-radius "75px")
(def radial-icon-radius "75px")
(def icon-color-scheme (str "radial-gradient(#52ABFF 5%, "
                            "#429EF5 60%,"
                            "#033882 70%)"))

(def base-icon-style {:border "1px solid black"
                      :text-align :center
                      :padding "5px"
                      :position "absolute"
                      :background-repeat "no-repeat"
                      :background-position-x "center"
                      :background-position-y "center"
                      :background-size "cover"
                      :border-radius "80px"})
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; RENDERING
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn expand-or-contract []
  (dispatch [:toggle-menu]))

(defn icon-click-handler [icon-url]
  (fn [] (dispatch [:click-radial-icon icon-url])))

(defn center-icon-style []
  (let [active-icon (subscribe [:active-icon])]
    (merge base-icon-style
           {:background-image (str @active-icon
                                   ", "
                                   icon-color-scheme)
            :width center-icon-radius
            :height center-icon-radius})))

(defn make-radial-icon-style [i icon-url]
  (let [radial-menu-open? (subscribe [:radial-menu-open?])
        animation (if @radial-menu-open?
                    (str "icon-" i "-open")
                    (str "icon-" i "-collapse"))]
    (merge base-icon-style
           {:background-image
            (str "url(" icon-url "), "
                 icon-color-scheme)
            :width radial-icon-radius
            :height radial-icon-radius
            :box-shadow "0 2px 5px 0 rgba(0, 0, 0, .26)"
            :animation-name animation
            :animation-duration "1s"
            :animation-fill-mode "forwards"})))

(defn home
  []
  (let [radial-menu-open? (subscribe [:radial-menu-open?])]
    [:div
     [nav/primary-nav]
     [:div#primary-content
      [article/primary-content]]
     [:div#radial-menu {:style {:height "275px"}}
      ((rcm/radial-menu)
       :radial-menu-name "radial-menu-1"
       :menu-radius "100px"
       :background-images icon-list
       :open? @radial-menu-open?
       :tooltip [:div#tooltip {:style {:text-align "left"
                                       :width "100px"}}
                 [:p "My button is here!"]]

       :center-icon-radius center-icon-radius
       :on-center-icon-click expand-or-contract
       :center-icon-style-fn center-icon-style

       :radial-icon-radius radial-icon-radius
       :on-radial-icon-click icon-click-handler
       :radial-icon-style-fn make-radial-icon-style)]
     [:div#rcb
      [cards/recent-content-display]]
     [loading/load-screen]]))
