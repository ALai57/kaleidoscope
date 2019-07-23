(ns andrewslai.cljs.views
  (:require [andrewslai.cljs.article :as article]
            [andrewslai.cljs.article-cards :as cards]
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
            ))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Landing pages
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn home
  []
  [:div
   [nav/primary-nav]
   [:div#primary-content
    [article/primary-content]]
   [:div#rcb
    [cards/recent-content-display]]
   [loading/load-screen]])

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

(def panels {:home [home]
             :thoughts [thoughts]
             :archive [archive]
             :about [about]
             :research [research]
             :data-analysis [data-analysis]
             :load-screen [load-page]})

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
