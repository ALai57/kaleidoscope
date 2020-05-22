(ns andrewslai.cljs.resume-cards
  (:require [reagent.core :refer [adapt-react-class]]
            [re-frame.core :refer [subscribe
                                   dispatch]]
            [cljsjs.react-bootstrap]
            [cljsjs.react-pose]
            [goog.object :as gobj]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ADAPT CLASSES
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def Card (adapt-react-class (aget js/ReactBootstrap "Card")))
(def PoseGroup (adapt-react-class js/PoseGroup))
(def PosedLi (adapt-react-class
               (js/posed.li (clj->js {:enter {:opacity 1}
                                      :before {:opacity 0.1}}))))
(def PosedH3 (adapt-react-class
               (js/posed.h3 (clj->js {:enter {:opacity 1}
                                      :before {:opacity 0.1}}))))
(def PosedCard (adapt-react-class
                 (js/posed.div (clj->js {:enter {:opacity 1}
                                         :exit {:opacity 0}
                                         :before {:opacity 0.1}}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MAKING THE CARDS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn make-card
  [{:keys [id name image_url url description] :as info} event-type selected-card]
  ^{:key (str name "-" id)}
  [Card {:class "text-white bg-light mb-3 article-card resume-info-card"
         :style {:border-radius "10px"}}
   [:div.container-fluid
    [:div.row.flex-items-xs-middle
     [:div.col-sm-3.bg-primary.text-xs-center.card-icon.resume-info-icon
      {:style {:border-radius "10px"}}
      [:div.p-y-3
       [:h1.p-y-2
        [:img.fa.fa-2x.resume-info-image
         {:src image_url
          :style {:width "100%" :height "50px"}
          :on-click
          (fn [x]
            (dispatch [:select-portfolio-card {:category event-type
                                               :name name}]))}]]]]
     [:div.col-sm-9.bg-light.text-dark.card-description
      [:h5.card-title>a {:href url}
       (:name info)]
      [:p.card-text description]]]]])

(defn make-posed-card
  [{:keys [name id] :as info} event-type selected-card]
  ^{:key (str "posed-" name "-" id)}
  [PosedCard {:style {:float "left"
                      :display "table-row"}}
   (make-card info event-type selected-card)])

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
