(ns andrewslai.cljs.pages.admin
  (:require [andrewslai.cljs.navbar :as nav]
            [andrewslai.cljs.modal :as modal]
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
    [:input {:type "button"
             :value "Create a new account!"
             :onClick #(dispatch [:set-active-panel :registration])}]
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
        (new (.getElementById js/document form-id))
        (.forEach (fn [v k obj] (swap! m conj {(keyword k) v}))))
    (swap! m assoc :avatar (-> js/document
                               (.getElementById "avatar-preview")
                               (aget "src")
                               (clojure.string/split ",")
                               second))
    @m))

(comment
  (let [m (atom {})]
    (-> js/FormData
        (new (.getElementById js/document "profile-update-form"))
        (.forEach (fn [v k obj] (swap! m conj {(keyword k) v}))))
    (swap! m assoc :avatar (-> js/document
                               (.getElementById "avatar-preview")
                               (aget "src")
                               (clojure.string/split ",")
                               second))
    @m)

  (let [n (atom {})]
    (-> js/FormData
        (new (.getElementById js/document "profile-update-form"))
        (.forEach (fn [v k obj] (swap! n conj {(keyword k) v}))))
    @n)

  (.log js/console (second
                     (clojure.string/split
                       (aget (.getElementById js/document "avatar-preview") "src")
                       ",")))

  )

;; TODO: POST to update user...
(defn load-image [file-added-event]
  (let [file (first (array-seq (.. file-added-event -target -files)))
        file-reader (js/FileReader.)]
    (set! (.-onload file-reader)
          (fn [file-load-event]
            (let [preview (.getElementById js/document "avatar-preview")]
              (aset preview "src" (-> file-load-event .-target .-result)))
            #_(reset! preview-src (-> file-load-event .-target .-result))))
    (.readAsDataURL file-reader file)))


(defn user-profile [{:keys [avatar_url username first_name last_name email] :as user}]
  [:div {:style {:margin "20px"}}
   [:form {:id "profile-update-form"
           :method :post
           :action "/echo"}
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

(defn my-awesome-modal-fn []
  [:input.btn-primary
   {:type "button"
    :title "Click to show modal!"
    :value "Show me the modal!"
    :on-click #(dispatch [:modal {:show? true
                                  :child [modal/hello-bootstrap]
                                  :size :small}])}])

;; TODO: Make user login timeout, so after 30 mins or so you can't see
;;       the profile information
(defn login-ui []
  (let [user (subscribe [:user])]
    [:div
     [modal/modal]
     [nav/primary-nav]
     [:br]
     (if @user
       [:div [user-profile @user]]
       [login-form])
     [my-awesome-modal-fn]]))

(defn register-user []
  [:div {:style {:margin "20px"}}
   [:form {:id "registration-form"
           :method :post
           :action "/echo"}
    [:img {:id "avatar-preview"
           :src "/images/smiley_emoji.png"
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
                                :defaultValue ""}]]]
    [editable-text-input "first_name" "First Name" ""]
    [editable-text-input "last_name" "Last Name" ""]
    [editable-text-input "email" "Email" ""]
    [:input {:type "password"
             :placeholder "Password"
             :name "password"}]
    #_[editable-text-input "password" "Password" ""]
    [:br]
    [:input.btn-primary
     {:type "button"
      :value "Create user!"
      :onClick
      (fn [& args]
        (dispatch [:register-user (form-data->map "registration-form")]))}]]])

(defn registration-ui []
  [:div
   [nav/primary-nav]
   [:br]
   [:div
    [register-user]]])
