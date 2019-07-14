(ns andrewslai.views
  (:require [reagent.core  :as reagent]
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
;; My website
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def Button (reagent/adapt-react-class (aget js/ReactBootstrap "Button")))
(def Card (reagent/adapt-react-class (aget js/ReactBootstrap "Card")))

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
  (let [title (get-in content [:article :title])
        style (:metadata
               (first
                (get-in content [:article :content])))]
    [:h2 style title]))

(defn format-js [js-script]
  (.appendChild (.getElementById js/document "primary-content")
                (doto (.createElement js/document "script")
                  (-> (.setAttribute "id" js-script))
                  (-> (.setAttribute "class" "dynamicjs"))
                  (-> (.setAttribute "src" (str "js/" js-script)))))
  [:div])

(defn format-content [content]
  (into
   [:div#article-content]
   (for [entry (sort-by :content_order content)]
     (condp = (:content_type entry)
       "text" ^{:key (:content_order entry)} [:p (:content entry)]
       "js" ^{:key (:content_order entry)} (format-js (:content entry))))))


(defn primary-content
  []
  (let [active-content (subscribe [:active-content])]
    ;;(println "Got active content! " @active-content)
    #_(when (not (nil? @active-content))
        (format-js "test-paragraph.js"))
    [:div#goodies
     (format-title @active-content)
     (format-content
      (get-in @active-content [:article :content]))]))


(defn make-card
  [{:keys [article_tags title article_url article_id] :as article}]

  (let [get-icon
        (fn [x] (condp = x
                  "research" "images/nav-bar/neuron-icon.svg"
                  "archive" "images/nav-bar/archive-icon.svg"
                  "about" "images/nav-bar/andrew-silhouette-icon.svg"
                  "thoughts" "images/nav-bar/andrew-head-icon.svg"))]

    ^{:key article_id}
    [Card {:class "text-white bg-light mb-3"
           :style {:max-width "18rem"
                   ;;:float "left"
                   :display "inline-block"
                   :min-width "350px"
                   :margin "10px"}}
     [:div.container-fluid
      [:div.row.flex-items-xs-middle
       [:div.col-md-3.bg-primary.text-xs-center.p-a-0
        {:style {:padding-top "1rem"
                 :padding-bottom "1rem"}}
        [:div.p-y-3
         [:h1.p-y-2
          [:img.fa.fa-2x {:src (get-icon article_tags)
                          :style {:width "100%"}}]]]]
       [:div.col-md-9.bg-light.text-dark.p-y-2 {:style {:height "100%"
                                                        :padding-top "0.5rem"}}
        [:h5.card-title>a {:href (str "#/" article_tags
                                      "/content/" article_url)}
         title]
        [:p.card-text article_url]]
       ]]]))

(defn recent-content-box
  []
  (let [recent-content (subscribe [:recent-content])]
    ;;(println "Got recent content! " @recent-content)
    #_(when (not (nil? @recent-content)))
    [:div#recent-content
     (format-title @recent-content)
     [:div#recent-article-cards.card-group {:style {:position "absolute"
                                                    :bottom 0
                                                    :justify-content "center"}}
      (map make-card @recent-content)]]))

(extend-protocol IPrintWithWriter
  js/Symbol
  (-pr-writer [sym writer _]
    (-write writer (str "\"" (.toString sym) "\""))))

;; TODO: Move to environment variable config?
(set! (.. cl-spinner -default -defaultProps -size) 150)
(set! (.. cl-spinner -default -defaultProps -color) "#4286f4")

(defn get-elements-in-class [class-name]
  (.getElementsByClassName js/document class-name))

(defn remove-dynamic-js []
  (while (not (nil? (.item (get-elements-in-class "dynamicjs") 0)))
    (.remove (.item (get-elements-in-class "dynamicjs") 0))))

(defn load-screen
  []
  (let [loading? (subscribe [:loading?])
        spinner-proto (.. cl-spinner -default -prototype)]
    (set! (.. spinner-proto -constructor -defaultProps -loading)
          (js->clj @loading?))
    (if (true? @loading?) (remove-dynamic-js))
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
   [:div#rcb
    [recent-content-box]]
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
