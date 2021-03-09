(ns andrewslai.cljs.pages.admin
  (:require [ajax.core :refer [POST]]
            [andrewslai.cljs.navbar :as nav]
            [andrewslai.cljs.keycloak :as keycloak]
            [andrewslai.cljs.server-comms.users :as user-comms]
            [andrewslai.cljs.modal :refer [close-modal modal-template] :as modal]
            [goog.object :as gobj]
            [re-frame.core :refer [dispatch subscribe]]
            [cljsjs.zxcvbn :as zxcvbn]
            [keycloak-js :as keycloak-js]))

(defn form-data->map [form-id]
  (let [m (atom {})]
    (-> js/FormData
        (new (.getElementById js/document form-id))
        (.forEach (fn [v k obj] (swap! m conj {(keyword k) v}))))
    @m))

(comment

  ;; How to handle the redirect url
  ;;  Because we're using hash routing, it is a little more difficult
  ;;  Need to put the code that parses the redirect right up front and center
  ;; https://github.com/okta/okta-auth-js#handling-the-callback-with-hash-routing
  (def base-keycloak-url
    "http://172.17.0.1:8080/auth")
  (def keycloak-auth-endpoint
    "/auth/realms/test/protocol/openid-connect/auth")

  (def keycloak
    (js/Keycloak (clj->js {:url base-keycloak-url
                           :realm "test"
                           :clientId "test-login"})))

  ;; Init keycloak
  (-> keycloak
      (.init (clj->js {:checkLoginIframe false
                       :pkceMethod "S256"}))
      (.then (fn [auth?]
               (println auth?)
               (js/console.log "auth status:" auth?)))
      (.catch (fn [auth?]
                (js/console.log "Unable to initialize Keycloak"))))

  ;; Login
  (.login keycloak (clj->js {:scope "roles"
                             :prompt "Please login to continue"
                             :redirectUri "http://localhost:3449/"}))

  (js/console.log keycloak)

  (-> keycloak
      (.init (clj->js {:onLoad "login-required"
                       :checkLoginIframe false
                       :pkceMethod "S256"}))
      (.then (fn [auth?]
               (println auth?)
               (js/console.log "auth status:" auth?)))
      (.catch (fn [auth?]
                (js/console.log "BAD LOGIN"))))


  )

;; TODO: Make sure refreshing the page doesn't clobber the authentication
(defn login-form []
  [:div.login-wrapper
   [:div.login-frame
    [:div.login-header [:h1 "Welcome!"]]
    [:p "andrewslai.com uses the open source "
     [:a {:href "https://www.keycloak.org"} "Keycloak"]
     " identity provider. Clicking the link will redirect you."]
    [:input.btn-secondary
     {:type "button"
      :value "Login via Keycloak"
      :onClick #(dispatch [:keycloak-login])}]
    [:br]
    [:br]
    [:input.btn-secondary
     {:type "button"
      :value "Try hitting restricted route"
      :onClick #(dispatch [:request-admin-route])}]
    [:br]]])

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


(defn user-profile [{:keys [avatar_url username firstName lastName email] :as user}]
  [:div.user-profile-wrapper
   [:form
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
     [:dt [:label {:for "email"} "Email"]]
     [:dd [:input.form-control {:type "text"
                                :readOnly true
                                :value email}]]]
    [:dl.form-group
     [:dt [:label {:for "firstName"} "First name"]]
     [:dd [:input.form-control {:type "text"
                                :readOnly true
                                :value firstName}]]]
    [:dl.form-group
     [:dt [:label {:for "lastName"} "Last name"]]
     [:dd [:input.form-control {:type "text"
                                :readOnly true
                                :value lastName}]]]
    [:br]
    [:br]
    [:input.btn-secondary
     {:type "button"
      :value "Edit profile"
      :style {:float "left"}
      :on-click (fn [& args]
                  (dispatch [:keycloak-account-management]))}]
    [:input.btn-secondary
     {:type "button"
      :value "Logout"
      :style {:float "right"}
      :on-click (fn [& args]
                  (dispatch [:keycloak-logout]))}]
    [:br]
    [:br]
    [:input.btn-secondary
     {:type "button"
      :value "Try hitting restricted route"
      :onClick #(dispatch [:request-admin-route])}]
    ]])

(defn login-ui []
  (let [user (subscribe [:update-user-profile!])]
    [:div
     [nav/primary-nav]
     [:br]
     (if @user
       [:div [user-profile @user]]
       [login-form])]))

;; For checking passwords client side
(comment
  (-> "_**asdfjekdCkzixcovh;e33h4383k3llsh"
      js/zxcvbn
      (js->clj :keywordize-keys true)
      (select-keys [:score :feedback]))
  )
