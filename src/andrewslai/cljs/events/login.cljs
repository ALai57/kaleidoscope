(ns andrewslai.cljs.events.login
  (:require [ajax.core :refer [POST]]
            [andrewslai.cljs.utils :refer [image->blob]]
            [andrewslai.cljs.modal :refer [modal-template close-modal]]
            [re-frame.core :refer [dispatch reg-event-db]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Done because each keystroke is saved...
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(reg-event-db
  :change-password
  (fn [db [_ password]]
    (assoc db :password password)))

(reg-event-db
  :change-username
  (fn [db [_ username]]
    (assoc db :username username)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; db events for logging in
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn process-login-response [db {:keys [avatar] :as user}]
  (if (empty? user)
    (assoc db :user nil)
    (let [updated-user (assoc user :avatar (image->blob avatar))]
      (assoc db :user updated-user))))

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

(defn process-bad-login [db username]
  (dispatch [:modal {:show? true?
                     :child (modal-template (login-failure username))
                     :size :small}])
  (assoc db :user nil))

(reg-event-db
  :login-click
  (fn [db [_ {:keys [username password :as user]}]]
    (POST "/login"
        {:params {:username username :password password}
         :format :json
         :handler #(dispatch [:process-http-response %1 process-login-response])
         :error-handler #(dispatch [:process-http-response
                                    username
                                    process-bad-login])})
    db))


;; TODO: Revoke blob/image URLs when logged out!
(defn process-logout-response [db response]
  (assoc db :user nil))

(reg-event-db
  :logout
  (fn [{:keys [username password] :as db}]
    (POST "/logout"
        {:handler #(dispatch [:process-http-response %1 process-logout-response])
         :error-handler #(dispatch [:process-http-response %1 identity])})
    (assoc db :user nil)))
