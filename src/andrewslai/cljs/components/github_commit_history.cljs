(ns andrewslai.cljs.components.github-commit-history
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
;; GITHUB DATA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce git-data (atom nil))

(def github-data-url "https://api.github.com/users/ALai57/events")

(defonce retrieve-commits
  (let [handle-response #(reset! git-data %)
        read-js (fn [x] (->> (pr/-body x)
                             (.parse js/JSON)
                             js->clj))]
    (GET github-data-url
        {:response-format
         (ring-response-format {:format
                                {:read read-js
                                 :description "JSON"
                                 :content-type ["application/json"]}})
         :handler handle-response})))

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

(defn- row->table [i r]
  ^{:key (str i)}
  [:tr nil
   [:td {:style {:min-width "7em"}} (take 10 (:created-at r))]
   [:td {:style {:white-space "nowrap"
                 :min-width "15em"}}
    [:a {:href (:repo-url r)}
     [:div {:style {:width "100%" :height "100%"}} (:repo r)]] ]
   [:td {:style {:white-space "nowrap"
                 :max-width "15em"}}
    [:a {:href (:commit-url r)}
     [:div {:style {:width "100%" :height "100%"}} (:message r)]]]])

(defn github-table [d]
  [:table nil
   [:thead nil
    [:tr nil
     [:th nil "Date"]
     [:th nil "Repo"]
     [:th nil "Message"]]]
   [:tbody nil
    (map-indexed row->table d)]])

(defn extract-commit-data [d]
  (let [{:keys [commits repo]} (js->clj d :keywordize-keys true)]
    (str "Repo: " repo "<br/>"
         "N commits: " commits)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; D3 helper functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn add-fade-to-class [m]
  (.. js/d3
      (select (:class m))
      (transition)
      (duration (:duration m))
      (style "opacity" (:opacity m))))

(defn set-class-properties [m]
  (.. js/d3
      (select ".tooltip")
      (html (:html m))
      (style "left" (str (:x m) "px"))
      (style "top" (str (:y m) "px"))))

(defn show-tooltip [d]
  (let [x (.-pageX js/d3.event)
        y (.-pageY js/d3.event)]
    (add-fade-to-class {:class ".tooltip"
                        :duration 200
                        :opacity 0.9})
    (set-class-properties {:html (extract-commit-data d)
                           :x x
                           :y y})))

(defn hide-tooltip []
  (add-fade-to-class {:class ".tooltip"
                      :duration 200
                      :opacity 0}))

(defn create-elements [m]
  (.. js/d3
      (select "svg")
      (selectAll (:el m))
      (data (:data m))
      enter
      (append (:el m))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; D3 main design
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn d3-inner [data]
  (let [basic-svg (fn [] [:div [:svg {:width 400 :height 300}]])]

    (reagent/create-class
     {:reagent-render basic-svg

      :component-did-mount
      (fn []
        (.. (create-elements {:el "circle"
                              :data (clj->js data)})
            (attr "cx" (fn [d] (->> (.-index d)
                                    (* 100)
                                    (+ 100))))
            (attr "cy" #(identity 100))
            (attr "r" (fn [d]
                        (->> (js->clj d :keywordize-keys true)
                             :commits
                             (* 3))))
            (attr "fill" #(identity "red"))
            (on "mouseover" show-tooltip)
            (on "mouseout" hide-tooltip)))

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
              (attr "cy" #(identity 100))
              (attr "r" (fn [d]
                          (->> (js->clj d :keywordize-keys true)
                               :commits
                               (* 3))))
              )))})))


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
     [:div {:class "row"}
      [:div {:class "col-md-5" :style {:width "400px"}}
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
