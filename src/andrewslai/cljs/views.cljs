(ns andrewslai.cljs.views
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
            [stylefy.core :refer [init]]
            ;;[cljs.pprint :as pprint]
            ))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Landing pages
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
  (let [radial-menu-open? (subscribe [:radial-menu-open?])
        _ (println "--- MENU OPEN? " @radial-menu-open?)]
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

(defn about
  []
  [:div
   [nav/primary-nav]
   [:p "About"]
   [:div#primary-content
    [article/primary-content]]
   [:div#rcb
    [cards/recent-content-display "about"]]
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

(defn circle-nav
  []
  [:div
   [nav/primary-nav]
   [:p "Test circle-nav"]
   [circle-nav/circle-nav]])

(def panels {:home [home]
             :thoughts [thoughts]
             :archive [archive]
             :about [about]
             :research [research]
             :data-analysis [data-analysis]
             :load-screen [load-page]
             :circle-nav [circle-nav]})

(defn app
  []
  (let [active-panel  (subscribe [:active-panel])]
    (fn []
      (get panels @active-panel))))


(comment
  (println "test Repl")
  (js-keys (.-body js/document))
  (.appendChild (.-body js/document)
                (doto (.createElement js/document "script")
                  (-> (.setAttribute "id" "testme-script"))
                  (-> (.setAttribute "src" "js/test-paragraph.js"))))
  (reframe/app-db)

  (cljs.pprint/pprint
   (get-in @re-frame.db/app-db [:active-content :article :content]))

  (format-content
   (get-in @re-frame.db/app-db [:active-content :article :content]))

  (:metadata
   (first (get-in @re-frame.db/app-db [:active-content :article :content])))

  (format-content
   (get-in @active-content [:article :content]))

  (for [el (.getElementsByClassName js/document "dynamicjs")]
    (.remove el))

  (.remove (.item (.getElementsByClassName js/document "dynamicjs") 0))

  (js-keys (.getElementsByClassName js/document "dynamicjs"))
  )
