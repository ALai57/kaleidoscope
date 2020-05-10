(ns andrewslai.cljs.server-comms.users
  (:require [ajax.core :refer [POST]]
            [andrewslai.cljs.modals.users :refer [registration-success-modal
                                                  registration-failure-modal]]
            [re-frame.core :refer [dispatch subscribe]]))

(defn register [user]
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
       (fn []
         (dispatch [:modal {:show? true
                            :child (registration-failure-modal user) 
                            :size :small}]))}))
