(ns andrewslai.cljs.components.radial-menu
  (:require [andrewslai.cljs.components.github-commit-history :as gh]
            [reagent.core  :as reagent]
            [re-frame.core :refer [subscribe
                                   dispatch
                                   reg-sub]]
            [cljsjs.react-bootstrap]
            [cljsjs.react-pose]
            ["react" :as react]
            [goog.object :as gobj]
            [stylefy.core :refer [init]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Init and settings
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(init)

(defn path->url [s]
  (str "url(" s ")"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Update this to use the new lib
;; reframe-components 0.3.0-SNAPSHOT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def icons
  {:teamwork {:image-url "images/teamwork.svg"}
   :github {:image-url "images/github.svg"}
   :cv {:image-url "images/cv.svg"}
   :tango {:image-url "images/tango-image-ccby.svg"}
   :volunteering {:image-url "images/volunteer.svg"}
   :clojure {:image-url "images/clojure-logo.svg"}
   :me {:image-url "images/my-silhouette.svg"}})

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

(defn icon-click-handler [icon]
  (fn [] (dispatch [:click-radial-icon icon])))

(defn center-icon-style []
  (let [active-icon (subscribe [:active-icon])
        [icon-name {:keys [image-url]}] @active-icon]
    (merge base-icon-style
           {:background-image
            (str (path->url image-url) ", " icon-color-scheme)
            :width center-icon-radius
            :height center-icon-radius})))

(defn make-radial-icon-style [i [icon-name {:keys [image-url]}]]
  (let [radial-menu-open? (subscribe [:radial-menu-open?])
        animation (if @radial-menu-open?
                    (str "icon-" i "-open")
                    (str "icon-" i "-collapse"))]
    (merge base-icon-style
           {:background-image
            (str (path->url image-url) ", " icon-color-scheme)
            :width radial-icon-radius
            :height radial-icon-radius
            :box-shadow "0 2px 5px 0 rgba(0, 0, 0, .26)"
            :animation-name animation
            :animation-duration "1s"
            :animation-fill-mode "forwards"})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TODO: Set up configuration for menu in this ns
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#_(def menu-contents {:me [me]
                      :clojure [clojure]
                      :volunteering [volunteering]
                      :tango [tango]
                      :cv [cv]
                      :github [gh/github]
                      :teamwork [teamwork]})

#_(defn rm []
    [:div#menu
     [:div#radial-menu {:style {:height "275px"}}
      ((rcm/radial-menu)
       :radial-menu-name "radial-menu-1"
       :menu-radius "100px"
       :icons rm/icons
       :open? @radial-menu-open?
       :tooltip [:div#tooltip {:style {:text-align "left"
                                       :width "100px"}}
                 [:p "My button is here!"]]

       :center-icon-radius rm/center-icon-radius
       :on-center-icon-click rm/expand-or-contract
       :center-icon-style-fn rm/center-icon-style

       :radial-icon-radius rm/radial-icon-radius
       :on-radial-icon-click rm/icon-click-handler
       :radial-icon-style-fn rm/make-radial-icon-style)]
     (get rm/menu-contents menu-item)])
