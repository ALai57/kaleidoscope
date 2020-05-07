(ns andrewslai.cljs.events.login
  (:require [ajax.core :refer [POST]]
            [andrewslai.cljs.utils :refer [image->blob]]
            [andrewslai.cljs.modal :refer [modal-template close-modal]]
            [re-frame.core :refer [dispatch reg-event-db]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; db events for logging in
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn logout [{:keys [username password] :as db}]
  (assoc db :user nil))
(reg-event-db
  :logout
  logout)


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

(defn invalid-login [db [_ username]]
  (dispatch [:modal {:show? true?
                     :child (modal-template (login-failure username))
                     :size :small}])
  db)
(reg-event-db
  :invalid-login
  invalid-login)


(defn login [db [_ user]]
  (assoc db :user user))
(reg-event-db
  :login
  login)

