(ns andrewslai.cljs.pages.home
  (:require [andrewslai.cljs.article :as article]
            [andrewslai.cljs.article-cards :as cards]
            [andrewslai.cljs.resume-cards :as resume-cards]
            [andrewslai.cljs.circle-nav :as circle-nav]
            [andrewslai.cljs.components.github-commit-history :as gh]
            [andrewslai.cljs.components.radial-menu :as rm]
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
                      :display "table-row"}}
   (resume-cards/make-card info event-type selected-card)])

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
        :icons rm/icons
        :open? @radial-menu-open?
        :tooltip [:div#tooltip {:style {:text-align "left"
                                        :width "100px"}}
                  [:p "My button is here!"]]

        :center-icon-radius rm/center-icon-radius
        :on-center-icon-click rm/expand-or-contract
        :center-icon-style-fn rm/center-icon-style

        :radial-icon-radius rm/radial-icon-radius
        :on-radial-icon-click rm/icon-click-handler
        :radial-icon-style-fn rm/make-radial-icon-style)]
      (get menu-contents menu-item)]
     [:div#rcb
      [cards/recent-content-display]]
     [loading/load-screen]]))


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

     ;; Language skills

     ;; Clojurescript
     ;; Ruby/ Rails
     ;; JS, CSS, HTML
     ;; C
     ;; Espanol?
     ;; Functional programming

     ;; Software tools
     ;; Reframe
     ;; React
     ;; Jenkins
     ;; Git

     ;; Research/Experimental skills
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
