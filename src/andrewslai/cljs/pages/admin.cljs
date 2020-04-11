(ns andrewslai.cljs.pages.admin
  (:require [andrewslai.cljs.navbar :as nav]
            [goog.object :as gobj]
            [re-frame.core :refer [dispatch subscribe]]))

;; TODO: Once authenticated, add image to database and use it
;; TODO: Make sure refreshing the page doesn't clobber the authentication

(defn login-form []
  [:div {:style {:text-align "center"}}
   [:h1 "Admin portal"]
   [:form
    [:input {:type "text"
             :placeholder "Username"
             :name "username"
             :on-change #(dispatch [:change-username (-> % .-target .-value)])}]
    [:br]
    [:input {:type "password"
             :placeholder "Password"
             :name "password"
             :on-change #(dispatch [:change-password (-> % .-target .-value)])}]
    [:br]
    [:input {:type "button"
             :value "Login"
             :onClick (fn [event]
                        (dispatch [:login-click event]))}]]])

(defn editable-text-input [field-name title initial-value & description]
  [:dl.form-group
   [:dt [:label {:for field-name} title]]
   [:dd [:input.form-control {:type "text"
                              :value initial-value}]]
   (when description
     [:note (first description)])])

;; TODO: Make uploadable avatar
;; TODO: POST to update user...
(defn user-profile [{:keys [avatar first_name last_name email] :as user}]
  [:div {:style {:margin "20px"}}
   [:img {:src avatar
          :style {:width "100px"}}]
   [:br]
   [:br]
   [:br]
   [editable-text-input "user_first_name" "First Name" first_name]
   [editable-text-input "user_last_name" "Last Name" last_name]
   [editable-text-input "user_email" "Email" email]])

;; TODO: Make user login timeout, so after 30 mins or so you can't see
;;       the profile information
(defn login-ui []
  (let [user (subscribe [:user])]
    [:div
     [nav/primary-nav]
     [:br]
     (if @user
       [:div [user-profile @user]]
       [login-form])]))
