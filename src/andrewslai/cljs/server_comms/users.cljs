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


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Login/logout
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn login [{:keys [username] :as creds}]
  (POST "/login"
      {:params creds
       :format :json
       :handler
       (fn [response]
         (dispatch [:load-user-profile response]))
       :error-handler
       (fn [response]
         (dispatch [:modal {:show? true
                            :child (login-failure-modal username)
                            :size :small}]))}))

(defn logout []
  (POST "/logout"
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
         (dispatch [:modal {:show? true
                            :child (registration-success-modal user)
                            :size :small}])
         (dispatch [:set-active-panel :admin]))
       :error-handler
       (fn [response]
         (dispatch [:modal {:show? true
                            :child (registration-failure-modal response)
                            :size :small}]))}))

(defn delete-user [{:keys [username] :as user}]
  (DELETE (str "/users/" username)
      {:params  user
       :format :json
       :handler
       (fn []
         (dispatch [:modal {:show? true
                            :child (delete-success-modal user)
                            :size :small}])
         (dispatch [:logout]))
       :error-handler
       (fn []
         (dispatch [:modal {:show? true
                            :child (delete-failure-modal user)
                            :size :small}]))}) )

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
         (dispatch [:modal {:show? true
                            :child (update-success-modal)
                            :size :small}])
         ;; TODO: DO SOMETHING WITH BROWSER CACHING
         (get-user username (fn [user] (dispatch [:load-user-profile user]))))

       :error-handler
       (fn [response]
         (dispatch [:modal {:show? true
                            :child (update-failure-modal response)
                            :size :small}]))}))
