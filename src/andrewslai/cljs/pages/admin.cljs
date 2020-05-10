(ns andrewslai.cljs.pages.admin
  (:require [ajax.core :refer [POST]]
            [andrewslai.cljs.navbar :as nav]
            [andrewslai.cljs.server-comms.users :as user-comms]
            [andrewslai.cljs.modal :refer [close-modal modal-template] :as modal]
            [goog.object :as gobj]
            [re-frame.core :refer [dispatch subscribe]]
            [cljsjs.zxcvbn :as zxcvbn]))

(defn form-data->map [form-id]
  (let [m (atom {})]
    (-> js/FormData
        (new (.getElementById js/document form-id))
        (.forEach (fn [v k obj] (swap! m conj {(keyword k) v}))))
    @m))

;; TODO: Make sure refreshing the page doesn't clobber the authentication
(defn login-form []
  [:div.login-wrapper
   [:div.login-frame
    [:div.login-header [:h1 "Welcome!"]]
    [:form {:id "login-form"}
     [:input {:type "text"
              :placeholder "Username"
              :name "username"}]
     [:br]
     [:input {:type "password"
              :placeholder "Password"
              :name "password"}]
     [:br]
     [:br]
     [:input.btn-primary
      {:type "button"
       :value "Login"
       :on-click (fn [& xs] (user-comms/login (form-data->map "login-form")))}]
     [:br]
     [:br]
     [:input.btn-secondary
      {:type "button"
       :value "Create a new account!"
       :onClick #(dispatch [:set-active-panel :registration])}]
     [:br]
     [:br]]]])

(defn text-input [field-name title initial-value & description]
  [:dl.form-group
   [:dt [:label {:for field-name} title]]
   [:dd [:input.form-control {:type "text"
                              :name field-name
                              :defaultValue initial-value}]]
   (when description
     [:note (first description)])])

(defn registration-data->map [form-id]
  (let [m (atom (form-data->map form-id))

        avatar (-> js/document
                   (.getElementById "avatar-preview")
                   (aget "src")
                   (clojure.string/split ",")
                   second)]

    (if avatar
      (swap! m assoc :avatar avatar)
      (swap! m dissoc :avatar))
    @m))


(defn load-image [file-added-event]
  (let [file (first (array-seq (.. file-added-event -target -files)))
        file-reader (js/FileReader.)]
    (set! (.-onload file-reader)
          (fn [file-load-event]
            (let [preview (.getElementById js/document "avatar-preview")]
              (aset preview "src" (-> file-load-event .-target .-result)))))
    (.readAsDataURL file-reader file)))


(defn confirm-delete-user [username]
  {:title (str "Really delete user: " username "?")
   :body
   [:div
    [:p "If you really want to delete user, enter credentials below to confirm"]
    [:div {:style {:text-align "center"}}
     [:form {:id "delete-user-input"}
      [:input {:type "text"
               :placeholder "Username"
               :name "username"}]
      [:br]
      [:input {:type "password"
               :placeholder "Password"
               :name "password"}]
      [:br]
      [:input
       {:type "button"
        :value "Delete user"
        :on-click
        (fn [event]
          (user-comms/delete-user (form-data->map "delete-user-input")))}]]]]
   :footer [:button {:type "button" :title "Cancel"
                     :class "btn btn-default"
                     :on-click #(close-modal)} "Close"]
   :close-fn close-modal})


(defn user-profile [{:keys [avatar_url username first_name last_name email] :as user}]
  [:div.user-profile-wrapper
   [:form {:id "profile-update-form"
           :method :post
           :action "/echo"}
    [:input.btn-danger
     {:type "button"
      :value "Delete user"
      :style {:float "right"}
      :on-click
      (fn [& args]
        (dispatch [:show-modal (modal-template (confirm-delete-user username))]))}]
    [:img {:src avatar_url
           :style {:width "100px"}}]
    [:img {:id "avatar-preview"
           :name "avatar"
           :style {:width "100px"}}]
    [:input.btn-primary
     {:type "file"
      :accept "image/png"
      :on-change load-image}]
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
    [:dl.form-group
     [:dt [:label {:for "email"} "Email"]]
     [:dd [:input.form-control {:type "text"
                                :readOnly true
                                :value email}]]
     [:note "Cannot be modified"]]
    [:br]
    [:br]
    [text-input "first_name" "First Name" first_name]
    [text-input "last_name" "Last Name" last_name]
    [:br]
    [:br]
    [:input.btn-primary
     {:type "button"
      :value "Update profile"
      :onClick
      (fn [& args]
        (user-comms/update-user (registration-data->map "profile-update-form")))}]
    [:input.btn-secondary
     {:type "button"
      :value "Logout"
      :style {:float "right"}
      :on-click
      (fn [& args]
        (user-comms/logout))}]]])


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

(defn register-user []
  [:div.user-profile-wrapper
   [:form {:id "registration-form"
           :method :post
           :action "/echo"}
    [:img.avatar-thumbnail {:id "avatar-preview"
                            :src "/images/smiley_emoji.png"}]
    [:input.btn-primary
     {:type "file"
      :accept "image/png"
      :on-change load-image}]
    [:br]
    [:br]
    [:br]
    [:dl.form-group
     [:dt [:label {:for "username"} "Username"]]
     [:dd [:input.form-control {:type "text"
                                :name "username"
                                :defaultValue ""}]]]
    [text-input "first_name" "First Name" ""]
    [text-input "last_name" "Last Name" ""]
    [text-input "email" "Email" ""]
    [:input {:type "password"
             :placeholder "Password"
             :name "password"}]
    [:br]
    [:input.btn-primary
     {:type "button"
      :value "Create user!"
      :on-click
      (fn [& args]
        (user-comms/register-user (registration-data->map "registration-form")))}]]])

(defn registration-ui []
  [:div
   [nav/primary-nav]
   [:br]
   [:div
    [register-user]]])

;; For checking passwords client side
(comment
  (-> "_**asdfjekdCkzixcovh;e33h4383k3llsh"
      js/zxcvbn
      (js->clj :keywordize-keys true)
      (select-keys [:score :feedback]))
  )
