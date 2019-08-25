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

#_(defn me []
    [:div#selected-menu-item
     [:h3#menu-title "About me"]])
(defn clojure []
  [:div#selected-menu-item
   [:h3#menu-title "Clojure"]])
(defn tango []
  [:div#selected-menu-item
   [:h3#menu-title "Tango"]])
(defn cv []
  [:div#selected-menu-item
   [:h3#menu-title "CV"]
   ])
(defn volunteering []
  [:div#selected-menu-item
   [:h3#menu-title "Volunteering"]])

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
                                    y (.-pageY js/d3.event)
                                    commits (:commits (js->clj d :keywordize-keys true))
                                    repo (:repo (js->clj d :keywordize-keys true))]
                                (.. js/d3
                                    (select ".tooltip")
                                    (transition)
                                    (duration 200)
                                    (style "opacity" 0.9))
                                (.. js/d3
                                    (select ".tooltip")
                                    (html (str "Repo: " repo "<br/>"
                                               "N commits: " commits
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TEAMWORK
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn teamwork []
  [:div#selected-menu-item
   [:h3#menu-title "Teamwork"]])


(def Card (reagent/adapt-react-class (aget js/ReactBootstrap "Card")))

(defn make-card
  [{:keys [image-url url title] :as info}]

  ^{:key url}
  [Card {:class "text-white bg-light mb-3 article-card"
         :style {:border-radius "10px"}}
   [:div.container-fluid
    [:div.row.flex-items-xs-middle
     [:div.col-sm-3.bg-primary.text-xs-center.card-icon
      {:style {:border-radius "10px"}}
      [:div.p-y-3
       [:h1.p-y-2
        [:img.fa.fa-2x {:src image-url
                        :style {:width "100%"}}]]]]
     [:div.col-sm-9.bg-light.text-dark.card-description
      [:h5.card-title>a {:href (str "#/no-link-yet")}
       title]
      [:p.card-text url]]]]])

(def cards
  [
   ;; Organizations
   {:image-url "images/ymca-logo.svg" :url "Project Soar" :title "YMCA"}
   {:image-url "images/vai-logo.svg" :url "Vietnamese Association of IL" :title "VAI"}
   {:image-url "images/chipy-logo.svg" :url "Chicago Python User Group" :title "ChiPy"}
   {:image-url "images/chi-hack-night-logo.svg" :url "Chi Hack Night" :title "Chi Hack Night"}
   {:image-url "images/nu-helix-logo.svg" :url "Helix Magazine" :title "HELIX"}
   {:image-url "images/center-for-leadership-logo.svg" :url "Northwestern Center for Leadership" :title "Center for Leadership"}
   {:image-url "images/mglc-logo.svg" :url "McCormick Graduate Leadership Council" :title "MGLC"}
   {:image-url "images/opploans-logo.svg" :url "OppLoans" :title "Opploans"}
   {:image-url "images/cephos-logo.svg" :url "Cephos Corporation" :title "Cephos"}
   {:image-url "images/lafayette-logo.svg" :url "Lafayette College" :title "Lafayette"}
   {:image-url "images/n-logo.svg" :url "Northwestern University" :title "Northwestern"}
   ;; Tea Gschwendner
   ;; Academic Approach
   ;; NUTango

   ;; Leadership
   ;; MGLC
   ;; NuTango
   ;; Opploans - Analytics manager

   ;; Projects
   ;; PIC32 infrared camera
   ;; Muscle synergies
   ;; Motor unit behavior
   ;; Muscle properties
   ;; Neural Networks explanaation in D3
   ;; Galvani article
   ;; Personal website
   ;; Launch Neuro ID
   ;; Software processing audit
   ;; DL orphans project
   ;; Science pentathlon

   ;; Certifications
   ;; PhD
   ;; Masters
   ;; BS Chem E
   {:image-url "images/datacamp-logo.svg" :url "Datacamp" :title "Datacamp"}

   ;; Languages
   {:image-url "images/python-logo.svg" :url "Python" :title "Python"}
   {:image-url "images/postgres-logo.svg" :url "PostgreSQL" :title "Postgres"}
   {:image-url "images/matlab-logo.svg" :url "Matlab" :title "Matlab"}
   {:image-url "images/docker-logo.svg" :url "Docker" :title "Docker"}
   {:image-url "images/terraform-logo.svg" :url "Terraform" :title "Terraform"}
   ;; Clojure
   ;; Clojurescript
   ;; JS, CSS, HTML
   ;; C
   ;; Espanol?

   ;; Analytics Tools
   {:image-url "images/periscope-logo.svg" :url "Periscope" :title "Periscope"}
   {:image-url "images/sumologic-logo.svg" :url "Sumologic" :title "Sumologic"}
   {:image-url "images/heap-logo.svg" :url "Heap Analytics" :title "Heap"}
   {:image-url "images/jupyter-logo.svg" :url "Jupyter Notebooks" :title "Jupyter"}
   {:image-url "images/pandas-logo.svg" :url "Pandas" :title "Pandas"}

   ;; Software tools
   {:image-url "images/emacs-logo.svg" :url "EMACS" :title "EMACS"}
   {:image-url "images/d3js-logo.svg" :url "D3" :title "D3"}
   ;; Reframe
   ;; React
   ;; Jenkins
   ;; Git

   ;; Research/Experimental skills
   {:image-url "images/ultrasound-logo.svg" :url "Shear wave elastography" :title "Shear wave elastography"}
   {:image-url "images/ultrasound-logo.svg" :url "B-mode ultrasound" :title "B-mode Ultrasound"}
   {:image-url "images/emg-logo.svg" :url "Surface EMG" :title "Surface Electromyography"}
   {:image-url "images/emg-logo.svg" :url "Single Motor Unit Analysis" :title "Single motor unit analysis"}

   ;; Data
   {:image-url "images/pipeline-logo.svg" :url "Build data pipeline" :title "Build data pipeline"}
   {:image-url "images/data-cleaning-logo.svg" :url "Data Cleaning" :title "Data Cleaning"}
   {:image-url "images/dimensionality-reduction-logo.svg" :url "NNMF" :title "Dimensionality Reduction"}
   {:image-url "images/hierarchical-mixed-models-logo.svg" :url "Statistical Modeling" :title "Hierarchical Mixed Modeling"}
   ;; Power spectral analysis
   ;; Clustering

   ;; Teaching
   ;; Scientific experimentation - TAing
   ;; Citizenship
   ;; ACT prep
   ;; Leadership Coaching
   ;; Tango
   ;; Youth mentoring - SOAR
   {:image-url "images/esl-logo.svg" :url "English as a Second Langauge" :title "ESL Teaching"}

   ;; MISC
   {:image-url "images/microchip-logo.svg" :url "Microcontroller App Development" :title "PIC32"}

   ;; Project management skills
   ;; Writing specs
   ;; Delivering results on time
   ;; Defining new directions
   {:image-url "images/aligning-stakeholders-logo.svg" :url "Project Management" :title "Aligning stakeholders"}

   ;; Skill profiles
   {:image-url "images/data-analysis-logo.svg" :url "Data analysis" :title "Data analysis"}
   {:image-url "images/data-storytelling-logo.svg" :url "Data storytelling" :title "Data storytelling"}
   {:image-url "images/cloud-deployment-logo.svg" :url "Cloud Deployment" :title "Cloud deployment"}
   {:image-url "images/backend-development-logo.svg" :url "Software Development" :title "Backend"}
   {:image-url "images/frontend-development-logo.svg" :url "Software Development" :title "Frontend"}
   {:image-url "images/project-management-logo.svg" :url "Project management" :title "Project management"}

   ;; TODO
   ;; Deployment
   ;; Dockerizing
   ;; Data visualization
   ;; Hypothesis testing
   ;; Teaching - BME 307
   ;; Identify anomalies
   ;; Process improvement
   ;; Designing and running experiments
   ;; Additional experiments
   ;; INFORMS
   ;; Publications
   ;; Pentathlon
   ;; ThematicMEN topics?
   ])

(defn me []
  [:div#selected-menu-item
   [:h3#menu-title "A bit about me!"]
   (map make-card cards)])

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
