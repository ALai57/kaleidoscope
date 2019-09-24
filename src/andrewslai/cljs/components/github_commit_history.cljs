(ns andrewslai.cljs.components.github-commit-history
  (:require [andrewslai.cljs.components.d3 :as d3]
            [reagent.core  :as reagent]
            [ajax.core :refer [GET]]
            [ajax.protocols :as pr]
            [ajax.ring :refer [ring-response-format]]
            [cljsjs.react-bootstrap]
            [cljsjs.d3]
            [cljsjs.react-pose]
            ["react" :as react]
            [goog.object :as gobj]))


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

(defn make-commit-table [d]
  [:table nil
   [:thead nil
    [:tr nil
     [:th nil "Date"]
     [:th nil "Repo"]
     [:th nil "Message"]]]
   [:tbody nil
    (map-indexed row->table d)]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; D3 main design
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn circles-and-tooltips [data]
  (let [basic-svg (fn [] [:div [:svg {:width 400 :height 300}]])
        cx (fn [d] (->> (.-index d)
                        (* 100)
                        (+ 100)))
        r (fn [d]
            (->> (js->clj d :keywordize-keys true)
                 :commits
                 (* 3)))]

    (reagent/create-class
     {:reagent-render basic-svg

      :component-did-mount
      (fn []
        (.. (d3/create-elements {:el "circle"
                                 :data (clj->js data)})
            (attr "fill" #(identity "red"))
            (attr "cx" cx)
            (attr "cy" #(identity 100))
            (attr "r" r)
            (on "mouseover" d3/show-tooltip)
            (on "mouseout" d3/hide-tooltip)))

      :component-did-update
      (fn [this]
        (let [[_ data] (reagent/argv this)
              d3data (clj->js data)]
          (.. js/d3
              (selectAll "circle")
              (data d3data)
              (attr "cx" cx)
              (attr "cy" #(identity 100))
              (attr "r" r))))})))

(defn flat-git-data []
  (flatten (map flattener (:body @git-data))))

;; TODO: Use entire commit history, not just recent
(defn commit-history-visualization []
  (let [commit-data
        (reduce #(update-in %1 [(:repo %2)] inc) {} (flat-git-data))

        indexed-data
        (map-indexed #(hash-map :index %1
                                :repo (first %2)
                                :commits (second %2)) commit-data)]
    (d3/create-tooltip)
    [:div {:class "container"}
     [:div {:class "row"}
      [:div {:class "col-md-5" :style {:width "400px"}}
       [circles-and-tooltips indexed-data]]]]))

(defn github []
  [:div#selected-menu-item
   [:h3#menu-title "Github"]
   [commit-history-visualization]
   [:div#table-wrapper {:style {:position "relative"}}
    [:div#table-scroll {:style {:width "100%"
                                :height "15em"
                                :overflow "auto"}}
     [make-commit-table (flat-git-data)]]]])

(comment
  ;; Retrieve Github data example
  (let [git-data-flat (flatten (map flattener (:body @git-data)))
        clj-data (reduce #(update-in %1 [(:repo %2)] inc) {} git-data-flat)]
    (map-indexed #(hash-map :index %1
                            :repo (first %2)
                            :n-commits (second %2)) clj-data))
  )
