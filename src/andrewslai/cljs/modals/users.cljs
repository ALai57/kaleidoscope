(ns andrewslai.cljs.modals.users
  (:require [andrewslai.cljs.modal :refer [modal-template close-modal]]))

(defn registration-success [{:keys [avatar_url username] :as user}]
  {:title "Successful user registration!"
   :body [:div
          [:img {:src avatar_url :style {:width "100px"}}]
          [:br]
          [:div [:p [:b "Username: "] username]]]
   :footer [:button {:type "button" :title "Ok"
                     :class "btn btn-default"
                     :on-click #(close-modal)} "Ok"]
   :close-fn close-modal})

(defn registration-success-modal [user]
  (modal-template (registration-success user)))


(defn registration-failure [{:keys [message type] :as payload}]
  (let [{:keys [feedback data]} message]
    {:title "User registration failed!"
     :body [:div {:style {:overflow-wrap "break-word"}}
            [:p [:b "Registration unsuccessful."]]
            [:p type]
            [:br]
            [:p (str feedback)]
            [:br]
            [:p reasons]]
     :footer [:button {:type "button" :title "Ok"
                       :class "btn btn-default"
                       :on-click #(close-modal)} "Ok"]
     :close-fn close-modal}))

(defn registration-failure-modal [user]
  (modal-template (registration-failure user)))
