(ns andrewslai.cljs.events.keycloak
  (:require [andrewslai.cljs.keycloak :as keycloak]
            [ajax.core :as ajax]
            [re-frame.core :refer [dispatch reg-event-db reg-event-fx reg-fx]]))


(reg-event-fx
 :initialize-keycloak
 (fn [cofx [_ _]]
   {:keycloak {:action   :init
               :instance (get-in cofx [:db :keycloak])
               :success  (fn on-success [auth?]
                           (when auth?
                             (dispatch [:set-hash-fragment "/admin"])
                             (dispatch [:keycloak-load-profile])))
               :fail     (fn on-error [e]
                           (js/console.log "Init error" e))}}))

(reg-event-fx
 :keycloak-login
 (fn [cofx _]
   {:keycloak {:action :login
               :instance (get-in cofx [:db :keycloak])}}))

(reg-event-fx
 :keycloak-logout
 (fn [cofx _]
   {:keycloak {:action :logout
               :instance (get-in cofx [:db :keycloak])}}))

(reg-event-fx
 :keycloak-load-profile
 (fn [cofx _]
   {:keycloak {:action   :load-profile
               :instance (get-in cofx [:db :keycloak])
               :success  (fn [result]
                           (dispatch [:update-user-profile!
                                      (js->clj result :keywordize-keys true)]))}}))

(reg-event-fx
 :keycloak-account-management
 (fn [cofx _]
   {:keycloak {:action :account-management
               :instance (get-in cofx [:db :keycloak])}}))

(defn keycloak-effect
  [{:keys [action instance success fail]}]
  (condp = action
    :account-management (keycloak/account-management! instance)
    :init               (keycloak/initialize! instance success fail)
    :load-profile       (keycloak/load-profile! instance)
    :login              (keycloak/login! instance)
    :logout             (keycloak/logout! instance)))

(reg-fx :keycloak keycloak-effect)


(defn hash-fragment-effect [path]
  (set! js/parent.location.hash path))

(reg-fx :hash-fragment hash-fragment-effect)

(reg-event-fx
 :set-hash-fragment
 (fn [cofx [_ path]]
   {:hash-fragment path}))

(reg-event-fx
 :request-admin-route
 (fn [{:keys [db]} [_]]
   (js/console.log "TOKEN" (.-token (:keycloak db)))
   {:http-xhrio {:method          :get
                 :uri             "/admin"
                 :headers         {:Authorization (str "Bearer " (.-token (:keycloak db)))}
                 :format          (ajax/json-response-format)
                 :response-format (ajax/json-response-format {:keywords? true})
                 :on-success      [:load-article]
                 :on-failure      [:load-article]}
    :db         (assoc db :loading? true)}))
