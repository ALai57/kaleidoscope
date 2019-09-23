(ns andrewslai.cljs.pages.home
  (:require [andrewslai.cljs.article :as article]
            [andrewslai.cljs.article-cards :as cards]
            [andrewslai.cljs.circle-nav :as circle-nav]
            [andrewslai.cljs.components.github-commit-history :as gh]
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

(defn clojure []
  [:div "Clojure"])

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
;; TEAMWORK
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn teamwork []
  [:div#selected-menu-item
   [:h3#menu-title "Teamwork"]])

#_(cljs.pprint/pprint (:resume-info @re-frame.db/app-db))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; RESUME INFO
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TODO: Two different views::
;; When user clicks on a heading - show a timeline, else show 3 rows
;; Make cards on a scroll wheel? Selects a single card at at time
;; When the card is selected, focus goes to that row, and the other rows
;;  populate accordingly -- e.g. I click on Project SOAR, and YMCA pops up
;;  on top and the mentoring and volunteering cards pop up on bottom

(def Card (reagent/adapt-react-class (aget js/ReactBootstrap "Card")))

(defn make-card
  [{:keys [id name image_url url description] :as info} event-type selected-card]
  ^{:key (str name "-" id)}
  [Card {:class "text-white bg-light mb-3 article-card resume-info-card"
         :style {:border-radius "10px"}}
   [:div.container-fluid
    [:div.row.flex-items-xs-middle {:style (if (= name selected-card)
                                             nil #_{:border-style "solid"
                                                    :border-width "5px"
                                                    :border-color "black"
                                                    :border-radius "10px"}
                                             nil)}
     [:div.col-sm-3.bg-primary.text-xs-center.card-icon.resume-info-icon
      {:style {:border-radius "10px"}}
      [:div.p-y-3
       [:h1.p-y-2
        [:img.fa.fa-2x.resume-info-image
         {:src image_url
          :style {:width "100%" :height "50px"}
          :onClick
          (fn [x]
            (dispatch [:click-resume-info-card event-type name]))}]]]]
     [:div.col-sm-9.bg-light.text-dark.card-description
      [:h5.card-title>a {:href url}
       (:name info)]
      [:p.card-text description]]]]])

;; Next commits:
;; [WIP] Skills - only select a single skill when clicked
;; Refactored

(def PoseGroup (reagent/adapt-react-class js/PoseGroup))
(def PosedLi (reagent/adapt-react-class
              (js/posed.li (clj->js {:enter {:opacity 1}
                                     :before {:opacity 0.1}}))))
(def PosedH3 (reagent/adapt-react-class
              (js/posed.h3 (clj->js {:enter {:opacity 1}
                                     :before {:opacity 0.1}}))))
(def PosedCard (reagent/adapt-react-class
                (js/posed.div (clj->js {:enter {:opacity 1}
                                        :exit {:opacity 0}
                                        :before {:opacity 0.1}}))))

(defn make-posed-card
  [{:keys [name id] :as info} event-type selected-card]
  ^{:key (str "posed-" name "-" id)}
  [PosedCard {:style {:float "left"
                      :display "table-row"}} (make-card info event-type selected-card)])

(defn make-posed-h3
  [name id]
  ^{:key (str "posed-" name "-" id)}
  [PosedCard {:style {:float "left"
                      :display "table-row"}} [:h3 name]])

(defn me []
  (let [resume-info (subscribe [:selected-resume-info])
        selected-card (subscribe [:selected-resume-card])]
    [:div#selected-menu-item
     [:div {:style {:float "left"}}
      [PoseGroup
       (make-posed-h3 "Organizations" "h3")
       [:br {:style {:clear "both"}}]
       (doall (map #(make-posed-card % :organization @selected-card)
                   (:organizations @resume-info)))
       [:br {:style {:clear "both"}}]
       (make-posed-h3 "Projects" "h3")
       [:br {:style {:clear "both"}}]
       (doall (map #(make-posed-card % :project @selected-card)
                   (:projects @resume-info)))
       [:br {:style {:clear "both"}}]
       (make-posed-h3 "Skills" "h3")
       [:br {:style {:clear "both"}}]
       (doall (map #(make-posed-card % :skill @selected-card)
                   (:skills @resume-info)))]]]))

#_(def cards
    [
     ;; Organizations
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

     ;; Language skills
     {:image-url "images/python-logo.svg" :url "Python" :title "Python"}
     {:image-url "images/postgres-logo.svg" :url "PostgreSQL" :title "Postgres"}
     {:image-url "images/matlab-logo.svg" :url "Matlab" :title "Matlab"}
     {:image-url "images/docker-logo.svg" :url "Docker" :title "Docker"}
     {:image-url "images/terraform-logo.svg" :url "Terraform" :title "Terraform"}
     ;; Clojure
     ;; Clojurescript
     ;; Ruby/ Rails
     ;; JS, CSS, HTML
     ;; C
     ;; Espanol?
     ;; Functional programming

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; RADIAL MENU
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def menu-contents {:me [me]
                    :clojure [clojure]
                    :volunteering [volunteering]
                    :tango [tango]
                    :cv [cv]
                    :github [gh/github]
                    :teamwork [teamwork]})

(defn home
  []
  (let [radial-menu-open? (subscribe [:radial-menu-open?])
        active-icon (subscribe [:active-icon])
        [menu-item icon-props] @active-icon]
    [:div#xyz {:onClick (fn [x]
                          (let [clicked-element (.-target x)
                                clicked-class (.-className clicked-element)]
                            (when-not (or (clojure.string/includes? clicked-class "resume-info-image")
                                          (clojure.string/includes? clicked-class "resume-info-icon")
                                          (clojure.string/includes? clicked-class "card-description")
                                          (clojure.string/includes? clicked-class "card-title")
                                          (clojure.string/includes? clicked-class "card-text"))
                              (dispatch [:reset-resume-info]))))}
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
