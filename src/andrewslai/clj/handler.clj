(ns andrewslai.clj.handler
  (:gen-class)
  (:require [aleph.http :as http]
            [andrewslai.clj.routes.andrewslai :as andrewslai]
            [andrewslai.clj.routes.wedding :as wedding]
            [andrewslai.clj.virtual-hosting :as vh]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Running the server
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn start-app
  [{:keys [port andrewslai wedding] :as configuration}]
  (http/start-server
   (vh/host-based-routing
    {#"caheriaguilar.and.andrewslai.com" {:priority 0
                                          :app      (wedding/wedding-app wedding)}
     #".*"                               {:priority 100
                                          :app      (andrewslai/andrewslai-app andrewslai)}})
   {:port port}))
