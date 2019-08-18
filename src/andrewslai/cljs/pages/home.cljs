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
            [cljsjs.d3]
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


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GITHUB DATA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
  (let [assoc-urls
        (fn [{:keys [repo commit-sha] :as commit}]
          (let [repo-url (str "https://github.com/" repo)
                commit-url (str repo-url "/commit/" commit-sha)]
            (-> commit
                (assoc :commit-url commit-url)
                (assoc :repo-url repo-url))))

        push-data {:type (get-in d ["type"])
                   :user (get-in d ["actor" "login"])
                   :repo (get-in d ["repo" "name"])
                   :repo-url (get-in d ["repo" "url"])
                   :created-at (get-in d ["created_at"])}

        commits (get-in d ["payload" "commits"])

        append-push-data
        (fn [commit-data]
          (merge push-data {:message (get-in commit-data ["message"])
                            :commit-sha (get-in commit-data ["sha"])}))]
    (map (comp assoc-urls append-push-data) commits)))

(defn github-table [d]
  (let [row->table
        (fn [i r]
          ^{:key (str i)}
          [:tr nil
           ;; TODO proper date parsing
           [:td {:style {:min-width "7em"}} (take 10 (:created-at r))]
           [:td {:style {:white-space "nowrap"
                         :min-width "15em"}}
            [:a {:href (:repo-url r)}
             [:div {:style {:width "100%" :height "100%"}} (:repo r)]] ]
           [:td {:style {:white-space "nowrap"
                         :max-width "15em"}}
            [:a {:href (:commit-url r)}
             [:div {:style {:width "100%" :height "100%"}} (:message r)]]]])]
    [:table nil
     [:thead nil
      [:tr nil
       [:th nil "Date"]
       [:th nil "Repo"]
       [:th nil "Message"]]]
     [:tbody nil
      (map-indexed row->table d)]]))

(defn d3-inner [data]
  (reagent/create-class
   {:reagent-render (fn [] [:div [:svg {:width 400 :height 300}]])

    :component-did-mount
    (fn []
      (let [d3data (clj->js data)]

        (.. js/d3
            (select "svg")
            (selectAll "circle")
            (data d3data)
            enter
            (append "circle")
            (attr "cx" (fn [d] (->> (.-index d)
                                    (* 100)
                                    (+ 100))))
            (attr "cy" (fn [d] 100))
            (attr "r" (fn [d]
                        (let [commits
                              (:commits (js->clj d :keywordize-keys true))]
                          (* 3 commits))))
            (attr "fill" (fn [d] "red"))
            (on "mouseover" (fn [d]
                              (let [x (.-pageX js/d3.event)
                                    y (.-pageY js/d3.event)]
                                (.. js/d3
                                    (select ".tooltip")
                                    (transition)
                                    (duration 200)
                                    (style "opacity" 0.9))
                                (.. js/d3
                                    (select ".tooltip")
                                    (html (str "Repo: " (.-repo d) "<br/>"
                                               "N commits: " (.-commits d)
                                               ))
                                    (style "left" (str x "px"))
                                    (style "top" (str y "px"))))))
            (on "mouseout" (fn [d]
                             (.. js/d3
                                 (select ".tooltip")
                                 (transition)
                                 (duration 200)
                                 (style "opacity" 0)))))))

    :component-did-update
    (fn [this]
      (let [[_ data] (reagent/argv this)
            d3data (clj->js data)]
        (.. js/d3
            (selectAll "circle")
            (data d3data)
            (attr "cx" (fn [d] (->> (.-index d)
                                    (* 100)
                                    (+ 100))))
            (attr "cy" (fn [d] 100))
            (attr "r" (fn [d]
                        (let [commits
                              (:commits (js->clj d :keywordize-keys true))]
                          (* 3 commits))))
            )))}))


(comment
  ;; USE GITHUB DATA
  (let [git-data-flat (flatten (map flattener (:body @git-data)))
        clj-data (reduce #(update-in %1 [(:repo %2)] inc) {} git-data-flat)]
    (map-indexed #(hash-map :index %1
                            :repo (first %2)
                            :n-commits (second %2)) clj-data))
  )

;; TODO: USE ENTIRE COMMIT HISTORY, NOT JUST RECENT
(defn commit-history-graph []
  (let [git-data-flat (flatten (map flattener (:body @git-data)))
        commit-data (reduce #(update-in %1 [(:repo %2)] inc) {} git-data-flat)
        indexed-data (map-indexed #(hash-map :index %1
                                             :repo (first %2)
                                             :commits (second %2)) commit-data)]
    (.. js/d3
        (select "body")
        (append "div")
        (attr "class" "tooltip")
        (style "opacity" 0))
    [:div {:class "container"}
     "Hey there!"
     [:div {:class "row"}
      [:div {:class "col-md-5"}
       [d3-inner indexed-data]]]]))

(defn github []
  (let [git-data-flat (flatten (map flattener (:body @git-data)))]
    [:div#selected-menu-item
     [:h3#menu-title "Github"]
     [commit-history-graph]
     [:div#table-wrapper {:style {:position "relative"}}
      [:div#table-scroll {:style {:width "100%"
                                  :height "15em"
                                  :overflow "auto"}}
       [github-table git-data-flat]]]]))

(defn teamwork []
  [:div#selected-menu-item
   [:h3#menu-title "Teamwork"]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; RADIAL MENU
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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





(comment
  (require '[ajax.core :refer [GET]])
  (defonce git-resp (atom nil))
  (GET "https://api.github.com/users/ALai57/events"
      {:handler (fn [response] (println (str response))
                  (println "hello!")
                  (reset! git-resp response))})
  (cljs.pprint/pprint (first @git-resp))

  (let [x (first @git-resp)]
    (println (str "https://github.com/"
                  (get-in x ["repo" "name"])
                  "/commit/"
                  (get-in x ["payload" "commits" 0 "sha"]))))

  
  (cljs.pprint/pprint (first @git-resp))

  (defn flattener [d]
    (let [base-data {:type (get-in d ["type"])
                     :user (get-in d ["actor" "login"])
                     :repo (get-in d ["repo" "name"])
                     :repo-url (get-in d ["repo" "url"])
                     :created-at (get-in d ["created_at"])}]
      (map #(merge base-data
                   {:message (get-in %1 ["message"])
                    :commit-sha (get-in %1 ["sha"])})
           (get-in d ["payload" "commits"]))))

  (cljs.pprint/pprint (map flattener @git-resp))

  (cljs.pprint/pprint (flattener (first @git-resp))))
