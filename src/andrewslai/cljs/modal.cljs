(ns andrewslai.cljs.modal
  (:require [re-frame.core :refer [dispatch subscribe]]))


(defn modal-panel
  [{:keys [child size show?]}]
  [:div {:class "modal-wrapper"}
   [:div {:class "modal-backdrop"
          :on-click (fn [event]
                      (do
                        (dispatch [:modal {:show? (not show?)
                                           :child nil
                                           :size :default}])
                        (.preventDefault event)
                        (.stopPropagation event)))}]
   [:div {:class "modal-child"
          :style {:width (case size
                           :extra-small "15%"
                           :small "30%"
                           :large "70%"
                           :extra-large "85%"
                           "50%")}} child]])

(defn modal []
  (let [modal (subscribe [:modal])]
    (.log js/console "OPened modal")
    (fn []
      [:div
       (if (:show? @modal)
         [modal-panel @modal])])))


(defn- close-modal []
  (dispatch [:modal {:show? false :child nil}]))

(defn hello-bootstrap []
  [:div {:class "modal-content panel-danger"}
   [:div {:class "modal-header panel-heading"
          :style {:background-color "#50B8A0"}}
    [:h4 {:class "modal-title"} "Hello Bootstrap modal!"]
    [:button.close {:type "button"
                    :style {:padding "0px"
                            :margin "0px"}
                    :title "Cancel"
                    :aria-label "Close"
                    :on-click #(close-modal)}
     [:span {:aria-hidden true} "x"]
     #_[:i {:class "material-icons"} "close"]]]
   [:div {:class "modal-body"}
    [:div [:b (str "You can close me by clicking the Ok button, the X in the"
                   " top right corner, or by clicking on the backdrop.")]]]
   [:div {:class "modal-footer"}
    [:button {:type "button" :title "Ok"
              :class "btn btn-default"
              :on-click #(close-modal)} "Ok"]]])
