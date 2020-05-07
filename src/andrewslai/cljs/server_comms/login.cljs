(ns andrewslai.cljs.server-comms.login
  (:require [ajax.core :refer [POST]]
            [re-frame.core :refer [dispatch subscribe]]))

(defn login [{:keys [username] :as creds}]
  (POST "/login"
      {:params creds
       :format :json
       :handler (fn [response] (dispatch [:login response]))
       :error-handler (fn [response] (dispatch [:invalid-login username]))}))
