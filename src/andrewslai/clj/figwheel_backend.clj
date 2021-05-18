(ns andrewslai.clj.figwheel-backend
  (:require [andrewslai.clj.handler :as h]
            [andrewslai.clj.persistence.postgres2 :as pg]
            [andrewslai.clj.utils :as util]
            [ring.middleware.session.memory :as mem]
            [taoensso.timbre :as log]))

;; For figwheel testing
(def figwheel-app
  (h/andrewslai-app {:database (pg/->Database (util/pg-conn))
                     :logging  (merge log/*config* {:level :info})}))
