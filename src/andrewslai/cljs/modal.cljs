(ns andrewslai.cljs.modal
  (:require [re-frame.core :refer [dispatch subscribe]]))

;;https://github.com/cljsjs/packages/tree/master/bootstrap-notify
;;https://github.com/Frozenlock/siren


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
          :style {:max-width "500px"
                  #_#_:width (case size
                               :extra-small "15%"
                               :small "30%"
                               :large "70%"
                               :extra-large "85%"
                               "50%")}} child]])

(defn modal []
  (let [modal (subscribe [:modal])]
    (fn []
      [:div
       (if (:show? @modal)
         [modal-panel @modal])])))


(defn close-modal []
  (dispatch [:modal {:show? false :child nil}]))

(defn modal-template [{:keys [title body footer close-fn]}]
  [:div {:class "modal-content panel-danger"}
   [:div {:class "modal-header panel-heading"
          :style {:background-color "#CA7B5E"}}
    [:h4 {:class "modal-title"} title]
    [:button.close {:type "button"
                    :style {:padding "0px"
                            :margin "0px"}
                    :title "Cancel"
                    :aria-label "Close"
                    :on-click (close-fn)}
     [:span {:aria-hidden true} "x"]]]
   [:div {:class "modal-body"}
    body]
   [:div {:class "modal-footer"}
    footer]])
