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

(def IllegalArgumentEx :andrewslai.clj.persistence.users/IllegalArgumentException)
(def PSQLEx :andrewslai.clj.persistence.users/PSQLException)

(defn registration-failure [{:keys [message type]}]
  (let [suggestions (get message :feedback)
        reasons (get message :data)]
    {:title "User registration failed!"
     :body [:div [:p [:b "Registration unsuccessful."]]
            [:p type]
            [:br]
            [:p suggestions]
            [:br]
            [:p reasons]]
     :footer [:button {:type "button" :title "Ok"
                       :class "btn btn-default"
                       :on-click #(close-modal)} "Ok"]
     :close-fn close-modal}))


(defn registration-success [{:keys [avatar_url username]}]
  {:title "Successful user registration!"
   :body [:div
          [:img {:src avatar_url :style {:width "100px"}}]
          [:br]
          [:div [:p [:b "Username: "] username]]]
   :footer [:button {:type "button" :title "Ok"
                     :class "btn btn-default"
                     :on-click #(close-modal)} "Ok"]
   :close-fn close-modal})

(defn process-register-user [db user]
  (dispatch [:modal {:show? true
                     :child (modal-template (registration-success user))
                     :size :small}])
  (dispatch [:set-active-panel :admin])
  db)

(defn process-unsuccessful-registration [db {:keys [response]}]
  (dispatch [:modal {:show? true
                     :child (modal-template (registration-failure response))
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
