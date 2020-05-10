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
            [:p data]]
     :footer [:button {:type "button" :title "Ok"
                       :class "btn btn-default"
                       :on-click #(close-modal)} "Ok"]
     :close-fn close-modal}))
(defn registration-failure-modal [{:keys [response]}]
  (modal-template (registration-failure response)))


(defn delete-failure [username]
  {:title "Unable to delete user"
   :body [:div {:style {:overflow-wrap "break-word"}}
          [:p [:b "Delete operation unsuccessful."]]
          [:br]
          [:p "User:" username]]
   :footer [:button {:type "button" :title "Ok"
                     :class "btn btn-default"
                     :on-click #(close-modal)} "Ok"]
   :close-fn close-modal})
(defn delete-failure-modal [username]
  (modal-template (delete-failure username)))

(defn delete-success [username]
  {:title "User successfully deleted!"
   :body [:div
          [:br]
          [:div [:p [:b "Username: "] username]]]
   :footer [:button {:type "button" :title "Ok"
                     :class "btn btn-default"
                     :on-click #(close-modal)} "Ok"]
   :close-fn close-modal})
(defn delete-success-modal [username]
  (modal-template (delete-success username)))


(defn update-success []
  {:title "User successfully updated!"
   :body [:div ]
   :footer [:button {:type "button" :title "Ok"
                     :class "btn btn-default"
                     :on-click #(close-modal)} "Ok"]
   :close-fn close-modal})
(defn update-success-modal []
  (modal-template (update-success)))

(defn update-failure [{:keys [message type] :as payload}]
  (let [{:keys [feedback data]} message]
    {:title "User update failed!"
     :body [:div {:style {:overflow-wrap "break-word"}}
            [:p [:b "Update unsuccessful."]]
            [:p type]
            [:br]
            [:p (str feedback)]
            [:br]
            [:p data]]
     :footer [:button {:type "button" :title "Ok"
                       :class "btn btn-default"
                       :on-click #(close-modal)} "Ok"]
     :close-fn close-modal}))
(defn update-failure-modal [payload]
  (modal-template (update-failure payload)))

(defn login-failure [username]
  {:title "Invalid username/password"
   :body [:div {:style {:overflow-wrap "break-word"}}
          [:p [:b "Login unsuccessful."]]
          [:br]
          [:p "User:" username]]
   :footer [:button {:type "button" :title "Ok"
                     :class "btn btn-default"
                     :on-click #(close-modal)} "Ok"]
   :close-fn close-modal})
(defn login-failure-modal [username]
  (modal-template (login-failure username)))
