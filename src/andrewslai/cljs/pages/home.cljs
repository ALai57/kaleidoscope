(ns andrewslai.cljs.pages.home
  (:require [andrewslai.cljs.article :as article]
            [andrewslai.cljs.article-cards :as cards]
            [andrewslai.cljs.navbar :as nav]
            [goog.object :as gobj]
            [re-frame.core :refer [dispatch]]))

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

(defn login-ui []
  [:form
   [:input {:type "text"
            :placeholder "Username"
            :name "username"
            :on-change #(dispatch [:change-username (-> % .-target .-value)])}]
   [:br]
   [:input {:type "text"
            :placeholder "Password"
            :name "password"
            :on-change #(dispatch [:change-password (-> % .-target .-value)])}]
   [:br]
   [:input {:type "button"
            :value "Login"
            :onClick (fn [event]
                       (dispatch [:login-click event]))}]])

(defn home []
  (println "Home")
  [:div
   [nav/primary-nav]
   [cards/recent-content-display]
   [login-ui]])

(comment
  (cljs.pprint/pprint (:resume-info @re-frame.db/app-db))
  (require '["react-bootstrap" :as react-bootstrap])

  )
