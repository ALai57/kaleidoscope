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

            [ajax.core :refer [GET]]
            [ajax.protocols :as pr]
            [ajax.ring :refer [ring-response-format]]
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
;; MENU CONTENTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment
  (require '[ajax.core :refer [GET]])
  (defonce git-resp (atom nil))
  (GET "https://api.github.com/users/ALai57/events"
      {:handler (fn [response] (println (str response))
                  (println "hello!")
                  (reset! git-resp response))})
  (cljs.pprint/pprint (first @git-resp))

  (defn flattener [d]
    (let [base-data {:type (get-in d ["type"])
                     :user (get-in d ["actor" "login"])
                     :repo (get-in d ["repo" "name"])
                     :repo-url (get-in d ["repo" "url"])
                     :created-at (get-in d ["created_at"])}]
      (map #(merge base-data {:message (get-in %1 ["message"])
                              :commit-url (get-in %1 ["url"])})
           (get-in d ["payload" "commits"]))))

  (cljs.pprint/pprint (map flattener @git-resp))

  (cljs.pprint/pprint (flattener (first @git-resp))))

(defn me []
  [:div#selected-menu-item
   [:h3#menu-title "About me"]])
(defn clojure []
  [:div#selected-menu-item
   [:h3#menu-title "Clojure"]])
(defn volunteering []
  [:div#selected-menu-item
   [:h3#menu-title "Volunteering"]])
(defn tango []
  [:div#selected-menu-item
   [:h3#menu-title "Tango"]])
(defn cv []
  [:div#selected-menu-item
   [:h3#menu-title "CV"]
   ])


(defonce git-data (atom nil))

(defonce _
  (GET "https://api.github.com/users/ALai57/events"
      {:response-format
       (ring-response-format {:format {:read (fn [x]
                                               (->> x
                                                    pr/-body
                                                    (.parse js/JSON)
                                                    js->clj))
                                       :description "JSON"
                                       :content-type ["application/json"]}})
       :handler (fn [response] (reset! git-data response))}))

(defn flattener [d]
  (let [base-data {:type (get-in d ["type"])
                   :user (get-in d ["actor" "login"])
                   :repo (get-in d ["repo" "name"])
                   :repo-url (get-in d ["repo" "url"])
                   :created-at (get-in d ["created_at"])}]
    (map #(merge base-data {:message (get-in %1 ["message"])
                            :commit-url (get-in %1 ["url"])})
         (get-in d ["payload" "commits"]))))

;; TODO clean up table formatting
;; TODO remove repo owner name from table (e.g. ALai57)
(defn github-table [d]
  (let [row->table
        (fn [r]
          [:tr nil
           ;; TODO proper date parsing
           [:td nil (take 10 (:created-at r))]
           [:td [:a {:href (:repo-url r)}
                 [:div {:style {:width "100%" :height "100%"}} (:repo r)]] ]
           [:td [:a {:href (:commit-url r)}
                 [:div {:style {:width "100%" :height "100%"}} (:message r)]]]])]
    [:table nil
     [:thead nil
      [:tr nil
       [:th nil "Date"]
       [:th nil "Repo"]
       [:th nil "Message"]]]
     [:tbody nil
      (map row->table d)]]))

(defn github []
  [:div#selected-menu-item
   [:h3#menu-title "Github"]
   [github-table (flatten (map flattener (:body @git-data)))]])


(defn teamwork []
  [:div#selected-menu-item
   [:h3#menu-title "Teamwork"]])

(def menu-contents {:me [me]
                    :clojure [clojure]
                    :volunteering [volunteering]
                    :tango [tango]
                    :cv [cv]
                    :github [github]
                    :teamwork [teamwork]})

(defn home
  []
  (let [radial-menu-open? (subscribe [:radial-menu-open?])
        active-icon (subscribe [:active-icon])
        [menu-item icon-props] @active-icon]
    [:div
     [nav/primary-nav]
     [:div#primary-content
      [article/primary-content]]
     [:div#menu
      [:div#radial-menu {:style {:height "275px"}}
       ((rcm/radial-menu)
        :radial-menu-name "radial-menu-1"
        :menu-radius "100px"
        :icons icons
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
      (get menu-contents menu-item)]
     [:div#rcb
      [cards/recent-content-display]]
     [loading/load-screen]]))
