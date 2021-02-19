(ns andrewslai.cljs.server-comms.projects-portfolio
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


(defn load-portfolio-cards! []
  (GET "/projects-portfolio"
      {:handler #(dispatch [:load-portfolio-cards %])
       :error-handler #(dispatch [:load-portfolio-cards "Unable to load content"])}))
