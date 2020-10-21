(ns andrewslai.cljs.server-comms.users
  (:require [ajax.core :refer [DELETE GET PATCH POST]]
            [andrewslai.cljs.modals.users :refer [registration-success-modal
                                                  registration-failure-modal
                                                  delete-success-modal
                                                  delete-failure-modal
                                                  update-success-modal
                                                  update-failure-modal
                                                  login-failure-modal]]
            [re-frame.core :refer [dispatch]]
            [clojure.string :as str]))

(defprotocol UserOperations
  (login-u [_ creds])
  (logout-u [_])
  (register [_ user])
  (delete [_ creds])
  (update-u [_ changes]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Login/logout
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn login [{:keys [username] :as creds}]
  (POST "/sessions/login"
      {:params creds
       :format :json
       :handler
       (fn [response]
         (dispatch [:load-user-profile response]))
       :error-handler
       (fn [response]
         (dispatch [:show-modal (login-failure-modal username)]))}))

(defn logout []
  (POST "/sessions/logout"
      {:handler (fn [] (dispatch [:logout]))
       :error-handler (fn [] (dispatch [:logout]))}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; User creation/deletion/modification
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn register-user [user]
  (POST "/users/"
      {:params user
       :format :json
       :handler
       (fn []
         (dispatch [:show-modal (registration-success-modal user)])
         (dispatch [:set-active-panel :admin]))
       :error-handler
       (fn [response]
         (dispatch [:show-modal (registration-failure-modal response)]))}))

(defn delete-user [{:keys [username] :as user}]
  (DELETE (str "/users/" username)
      {:params  user
       :format :json
       :handler
       (fn []
         (dispatch [:show-modal (delete-success-modal user)])
         (dispatch [:logout]))
       :error-handler
       (fn []
         (dispatch [:show-modal (delete-failure-modal user)]))}) )

(defn get-user [username & [callback]]
  (GET (str "/users/" username)
      {:handler
       (or callback
           (fn [user] (println "Found user:" user)))
       :on-error
       (fn []
         (println "Could not load user"))}))

(defn update-user [{:keys [username] :as request}]
  (PATCH (str "/users/" username)
      {:params request
       :format :json
       :handler
       (fn [user]
         (dispatch [:show-modal (update-success-modal)])
         (get-user username (fn [user] (dispatch [:load-user-profile user]))))

       :error-handler
       (fn [response]
         (dispatch [:show-modal (update-failure-modal response)]))}))

(defn user-ops []
  (reify UserOperations
    #_(login [_ creds]
        (-login))
    #_(logout [_]
        (-logout))
    (register [_ user]
      (register-user user))
    (delete [_ creds]
      (delete-user creds))
    (update-u [_ changes]
      (update-user changes))))
