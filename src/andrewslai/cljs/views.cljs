(ns andrewslai.cljs.views
  (:require [andrewslai.cljs.article :as article]
            [andrewslai.cljs.article-cards :as cards]
            [andrewslai.cljs.navbar :as nav]
            [andrewslai.cljs.pages.home :refer [home]]
            [andrewslai.cljs.pages.editor :refer [editor-ui]]
            [andrewslai.cljs.pages.admin :refer [login-ui
                                                 registration-ui]]
            [andrewslai.cljs.resume-cards :as resume-cards]
            [clojure.string :refer [includes?]]
            [re-frame.core :refer [subscribe
                                   dispatch]]))

;; Add a login panel
;; Redirect with a "Welcome User" message upon login
;; Add a user Icon/avatar so we know when you're logged in


;; Write tests with cookies....
;; https://github.com/reagent-project/reagent-utils/blob/master/test/reagent/cookies_test.cljs
;; https://github.com/SMX-LTD/re-frame-cookie-fx
;; TODO: Send token in all client transactions

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

(defn reset-portfolio-cards [x]
  (let [clicked-element (.-target x)
        clicked-class (.-className clicked-element)]
    (when-not (or (includes? clicked-class "resume-info-image")
                  (includes? clicked-class "resume-info-icon")
                  (includes? clicked-class "card-description")
                  (includes? clicked-class "card-title")
                  (includes? clicked-class "card-text"))
      (dispatch [:reset-portfolio-cards]))))

(defn about []
  [:div {:onClick reset-portfolio-cards
         :style {:height "100%"
                 :width "100%"
                 :position "absolute"}}
   [nav/primary-nav]
   [:div {:style {:height "100%"}}
    [resume-cards/me]]])

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
             :admin [login-ui]
             :registration [registration-ui]
             :editor [editor-ui]
             })

(defn app []
  (let [active-panel (subscribe [:active-panel])]
    (fn []
      (println "active panel" active-panel)
      (get panels @active-panel))))
