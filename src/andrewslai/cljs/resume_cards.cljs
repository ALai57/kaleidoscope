(ns andrewslai.cljs.resume-cards
  (:require [reagent.core  :as reagent]
            [re-frame.core :refer [dispatch]]
            [cljsjs.react-bootstrap]
            [cljsjs.d3]
            [cljsjs.react-pose]
            ["react" :as react]
            [goog.object :as gobj]))


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
