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

(defn format-title [content]
  [:h2 (get-in content [:article :title])])

(defn format-js [js-script]
  (.appendChild (.getElementById js/document "primary-content")
                (doto (.createElement js/document "script")
                  (-> (.setAttribute "id" js-script))
                  (-> (.setAttribute "src" (str "js/" js-script)))))
  [:div])

(defn format-content [content]
  (for [entry (sort-by :content_order content)]
    (do
      (println (:content_type entry))
      (condp = (:content_type entry)
        "text" [:p (:content entry)]
        "js" (format-js (:content entry))))))


(defn primary-content
  []
  (let [active-content (subscribe [:active-content])]
    (println "Got active content! " @active-content)
    #_(when (not (nil? @active-content))
        (format-js "test-paragraph.js"))
    [:div#goodies
     (format-title @active-content)
     (format-content
      (get-in @active-content [:articles :content]))]))


(extend-protocol IPrintWithWriter
  js/Symbol
  (-pr-writer [sym writer _]
    (-write writer (str "\"" (.toString sym) "\""))))

;; TODO: Move to environment variable config?
(set! (.. cl-spinner -default -defaultProps -size) 150)
(set! (.. cl-spinner -default -defaultProps -color) "#4286f4")

(defn load-screen
  []
  (let [loading? (subscribe [:loading?])
        spinner-proto (.. cl-spinner -default -prototype)]
    (set! (.. spinner-proto -constructor -defaultProps -loading)
          (js->clj @loading?))
    [:div#loading #_{:class "load-icon"
                     :style {:float "left"
                             :margin "auto"}}
     (.render spinner-proto)]))

(defn home
  []
  [:div
   [primary-nav]
   [:div#primary-content
    [primary-content]]
   [load-screen]])

(defn thoughts
  []
  [:div
   [primary-nav]
   [:p "Thoughts"]
   [:div#primary-content
    [primary-content]]
   [load-screen]])

(defn archive
  []
  [:div
   [primary-nav]
   [:p "Archive"]
   [:div#primary-content
    [primary-content]]
   [load-screen]])

(defn about
  []
  [:div
   [primary-nav]
   [:p "About"]
   [:div#primary-content
    [primary-content]]
   [load-screen]])

(defn research
  []
  [:div
   [primary-nav]
   [:p "Research"]
   [:div#primary-content
    [primary-content]]
   [load-screen]])

(defn data-analysis
  []
  [:div
   [primary-nav]
   [:p "Data Analysis"]
   [:div#primary-content
    [primary-content]]
   [load-screen]])

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
  (println "test Repl")
  (js-keys (.-body js/document))
  (.appendChild (.-body js/document)
                (doto (.createElement js/document "script")
                  (-> (.setAttribute "id" "testme-script"))
                  (-> (.setAttribute "src" "js/test-paragraph.js"))))
  (reframe/app-db)

  (cljs.pprint/pprint
   (get-in @re-frame.db/app-db [:active-content :articles :content]))

  (format-content
   (get-in @re-frame.db/app-db [:active-content :articles :content]))
  )

;; GET CONTENTS TO DELETE PROPERLY!!
