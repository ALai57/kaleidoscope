(ns andrewslai.cljs.modals.admin
  (:require [andrewslai.cljs.modal :refer [modal-template close-modal]]))

(defn authentication-failure [payload]
  {:title "Authentication failed!"
   :body [:div {:style {:overflow-wrap "break-word"}}
          [:p [:b "Not Authenticated"]]
          [:br]
          [:p (str payload)]
          [:br]]
   :footer [:button {:type "button" :title "Ok"
                     :class "btn btn-default"
                     :on-click #(close-modal)} "Ok"]
   :close-fn close-modal})
(defn authentication-failure-modal [payload]
  (modal-template (authentication-failure payload)))


(defn authentication-success [payload]
  {:title "Authentication success!"
   :body [:div {:style {:overflow-wrap "break-word"}}
          [:p [:b "Authenticated user"]]
          [:br]
          [:p (str payload)]
          [:br]]
   :footer [:button {:type "button" :title "Ok"
                     :class "btn btn-default"
                     :on-click #(close-modal)} "Ok"]
   :close-fn close-modal})
(defn authentication-success-modal [payload]
  (modal-template (authentication-success payload)))
