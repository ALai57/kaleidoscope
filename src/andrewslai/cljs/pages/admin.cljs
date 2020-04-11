(ns andrewslai.cljs.pages.admin
  (:require [andrewslai.cljs.navbar :as nav]
            [goog.object :as gobj]
            [re-frame.core :refer [dispatch subscribe]]))

;; TODO: Once authenticated, add image to database and use it
;; TODO: Make sure refreshing the page doesn't clobber the authentication

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

(defn image->blob [the-bytes]
  (let [data-blob (js/Blob. #js [the-bytes] #js {:type "image/jpeg"})]
    (.createObjectURL js/URL data-blob)))

(defn user-profile []
  (let [user-info (subscribe [:active-user])
        avatar (subscribe [:avatar])]
    (println "User info" @user-info)
    [:div
     ;;[:p "Active user" @user-info]
     (when @avatar
       [:img {:src (image->blob @avatar)}])]))

(defn login-ui []
  [:div
   [nav/primary-nav]
   [:br]
   [:div {:style {:text-align "center"}}
    [:h1 "Admin portal"]
    [login-form]
    [user-profile]]])
