(ns andrewslai.clj.figwheel-backend
  (:require [andrewslai.clj.handler :as h]
            [andrewslai.clj.persistence.postgres2 :as pg]
            [andrewslai.clj.utils :as util]
            [ring.middleware.session.memory :as mem]
            [taoensso.timbre :as log]))

;; For figwheel testing
(def figwheel-app
  (h/wrap-middleware h/app-routes
                     {:database (pg/->Database (util/pg-conn))
                      :logging  (merge log/*config* {:level :info})
                      :session  {:cookie-attrs {:max-age 3600 :secure true}
                                 :store        (mem/memory-store (atom {}))}}))
