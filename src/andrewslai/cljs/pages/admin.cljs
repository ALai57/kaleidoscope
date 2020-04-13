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
                        (dispatch [:login-click event]))}]
    [:br]
    ]])

(defn editable-text-input [field-name title initial-value & description]
  [:dl.form-group
   [:dt [:label {:for field-name} title]]
   [:dd [:input.form-control {:type "text"
                              :name field-name
                              :defaultValue initial-value}]]
   (when description
     [:note (first description)])])

(defn form-data->map [form-id]
  (let [m (atom {})]
    (-> js/FormData
        (new (.getElementById js/document "profile-update-form"))
        (.forEach (fn [v k obj] (swap! m conj {(keyword k) v}))))
    @m))

;; TODO: Make uploadable avatar
;; TODO: POST to update user...
(defn user-profile [{:keys [avatar username first_name last_name email] :as user}]
  [:div {:style {:margin "20px"}}
   [:form {:id "profile-update-form"
           :method :post
           :action "/echo"}
    [:img {:src avatar
           :style {:width "100px"}}]
    [:br]
    [:br]
    [:br]
    [:dl.form-group
     [:dt [:label {:for "username"} "Username"]]
     [:dd [:input.form-control {:type "text"
                                :name "username"
                                :readOnly true
                                :value username}]]
     [:note "Cannot be modified"]]
    [editable-text-input "first_name" "First Name" first_name]
    [editable-text-input "last_name" "Last Name" last_name]
    [editable-text-input "email" "Email" email]
    [:br]
    [:input.btn-primary
     {:type "button"
      :value "Update profile"
      :onClick
      (fn [& args]
        (dispatch [:update-profile (form-data->map "profile-update-form")]))}]
    [:input.btn-secondary
     {:type "button"
      :value "Logout"
      :style {:float "right"}
      :onClick (fn [& args] (dispatch [:logout]))}]]])

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
