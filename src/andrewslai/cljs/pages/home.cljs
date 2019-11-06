(ns andrewslai.cljs.pages.home
  (:require [andrewslai.cljs.article :as article]
            [andrewslai.cljs.article-cards :as cards]
            [andrewslai.cljs.resume-cards :as resume-cards]
            [andrewslai.cljs.circle-nav :as circle-nav]
            [andrewslai.cljs.components.github-commit-history :as gh]
            [andrewslai.cljs.components.radial-menu :as rm]
            [andrewslai.cljs.loading :as loading]
            [andrewslai.cljs.navbar :as nav]
            [reagent.core  :as reagent]
            [re-frame.core :refer [subscribe
                                   dispatch
                                   reg-sub]]

            [ajax.core :refer [GET]]
            [ajax.protocols :as pr]
            [ajax.ring :refer [ring-response-format]]
            [cljsjs.react-bootstrap]
            [cljsjs.react-transition-group :as rtg]
            [cljsjs.d3]
            [cljsjs.react-pose]
            ["react" :as react]
            ["react-spinners" :as spinner]
            ["emotion" :as emotion]
            [goog.object :as gobj]
            [reframe-components.recom-radial-menu :as rcm]
            [stylefy.core :refer [init]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MENU CONTENTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn clojure []
  [:div "Clojure"])

(defn tango []
  [:div#selected-menu-item
   [:h3#menu-title "Tango"]])

(defn cv []
  [:div#selected-menu-item
   [:h3#menu-title "CV"]])

(defn volunteering []
  [:div#selected-menu-item
   [:h3#menu-title "Volunteering"]])

(defn teamwork []
  [:div#selected-menu-item
   [:h3#menu-title "Teamwork"]])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; RESUME INFO
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TODO: Two different views::
;; When user clicks on a heading - show a timeline, else show 3 rows
;; Make cards on a scroll wheel? Selects a single card at at time
;; When the card is selected, focus goes to that row, and the other rows
;;  populate accordingly -- e.g. I click on Project SOAR, and YMCA pops up
;;  on top and the mentoring and volunteering cards pop up on bottom

(def menu-contents {:me [resume-cards/me]
                    :clojure [clojure]
                    :volunteering [volunteering]
                    :tango [tango]
                    :cv [cv]
                    :github [gh/github]
                    :teamwork [teamwork]})

(defn reset-resume-info [x]
  (let [clicked-element (.-target x)
        clicked-class (.-className clicked-element)]
    (when-not (or (clojure.string/includes? clicked-class "resume-info-image")
                  (clojure.string/includes? clicked-class "resume-info-icon")
                  (clojure.string/includes? clicked-class "card-description")
                  (clojure.string/includes? clicked-class "card-title")
                  (clojure.string/includes? clicked-class "card-text"))
      (dispatch [:reset-resume-info]))))

(defn home []
  (let [radial-menu-open? (subscribe [:radial-menu-open?])
        active-icon (subscribe [:active-icon])
        [menu-item icon-props] @active-icon]
    [:div 
     [nav/primary-nav]
     [cards/recent-content-display]
     [loading/load-screen]]))

(comment
  (cljs.pprint/pprint (:resume-info @re-frame.db/app-db))
  )
