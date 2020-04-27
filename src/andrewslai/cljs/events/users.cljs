(ns andrewslai.cljs.events.users
  (:require [ajax.core :refer [PATCH POST]]
            [andrewslai.cljs.events.core :refer [modify-db]]
            [andrewslai.cljs.utils :refer [image->blob]]
            [andrewslai.cljs.modal :refer [modal-template close-modal]]
            [re-frame.core :refer [dispatch reg-event-db]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; db events for updating profile
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO: Right now, we don't have a good way to know if the update was successful
;;       or not. Need to make the update endpoint send different status codes
;;       depending on whether the update was successful or not.
;;       Also need to update this event handler to handle both cases

;; TODO: Support avatar uploads in this function too. Right now it will be
;;       unhappy/unable to support conversion of an avatar image into a blob

(defn process-profile-update [db {:keys [avatar] :as user}]
  (if (empty? user)
    (assoc db :user nil)
    (assoc db :user (merge (:user db) (if avatar
                                        (assoc user
                                               :avatar
                                               (image->blob avatar))
                                        user)))))

(reg-event-db
  :update-profile
  (fn [db [_ {:keys [username] :as request}]]

    (PATCH (str "/users/" username)
        {:params request
         :format :json
         :handler #(dispatch [:process-http-response % process-profile-update])
         :error-handler #(dispatch [:process-http-response % identity])})

    db))

(defn- close-modal []
  (dispatch [:modal {:show? false :child nil}]))

(defmulti user-registration-response type)

(defmethod user-registration-response js/String
  [error]
  [:div {:class "modal-content panel-danger"}
   [:div {:class "modal-header panel-heading"
          :style {:background-color "#B85068"}}
    [:h4 {:class "modal-title"} "Unsuccessful user registration!"]
    [:button.close {:type "button"
                    :style {:padding "0px"
                            :margin "0px"}
                    :title "Cancel"
                    :aria-label "Close"
                    :on-click #(close-modal)}
     [:span {:aria-hidden true} "x"]]]
   [:div {:class "modal-body"}
    [:div [:p [:b "Registration unsuccessful."]]
     [:p error]]]
   [:div {:class "modal-footer"}
    [:button {:type "button" :title "Ok"
              :class "btn btn-default"
              :on-click #(close-modal)} "Ok"]]])

(defmethod user-registration-response cljs.core/PersistentArrayMap
  [{:keys [avatar_url username] :as user}]
  [:div {:class "modal-content panel-danger"}
   [:div {:class "modal-header panel-heading"
          :style {:background-color "#50B8A0"}}
    [:h4 {:class "modal-title"} "Successful user registration!"]
    [:button.close {:type "button"
                    :style {:padding "0px"
                            :margin "0px"}
                    :title "Cancel"
                    :aria-label "Close"
                    :on-click #(close-modal)}
     [:span {:aria-hidden true} "x"]]]
   [:div {:class "modal-body"}
    [:img {:src avatar_url
           :style {:width "100px"}}]
    [:div [:b (str "Username: " username)]]]
   [:div {:class "modal-footer"}
    [:button {:type "button" :title "Ok"
              :class "btn btn-default"
              :on-click #(close-modal)} "Ok"]]])


(defn process-register-user [db user]
  (dispatch [:modal {:show? true
                     :child [user-registration-response user]
                     :size :small}])
  (dispatch [:set-active-panel :admin])
  db)

(defn process-unsuccessful-registration [db user]
  (dispatch [:modal {:show? true
                     :child [user-registration-response (get-in user [:response :message :reason])]
                     :size :small}])
  db)

(reg-event-db
  :register-user
  (fn [db [_ user]]

    (POST "/users/"
        {:params user
         :format :json
         :handler #(dispatch [:process-http-response % process-register-user])
         :error-handler #(dispatch [:process-http-response % process-unsuccessful-registration])})

    db))
