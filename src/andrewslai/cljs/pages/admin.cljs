(ns andrewslai.cljs.pages.admin
  (:require [andrewslai.cljs.navbar :as nav]
            [goog.object :as gobj]
            [re-frame.core :refer [dispatch subscribe]]))


(defn login-form []
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
                       (dispatch [:login-click event]))}]])

(defn user-profile []
  (let [user-info (subscribe [:active-user])]
    (println "User info" @user-info)
    [:div
     [:p "Active user" @user-info]]))

(defn login-ui []
  [:div
   [nav/primary-nav]
   [:br]
   [:div {:style {:text-align "center"}}
    [:h1 "Admin portal"]
    [login-form]
    [user-profile]]])
