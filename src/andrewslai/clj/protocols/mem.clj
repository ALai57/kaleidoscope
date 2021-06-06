(ns andrewslai.clj.protocols.mem
  (:require [andrewslai.clj.protocols.core :as protocols]
            [ring.util.response :as ring-response]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Install multimethod to get resource-data from URLs using MEM-PROTOCOL
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def MEM-PROTOCOL
  "In memory protocol"
  "mem")

(def loader
  (partial protocols/filesystem-loader MEM-PROTOCOL))

(defmethod ring-response/resource-data (keyword MEM-PROTOCOL)
  [url]
  (let [conn (.openConnection url)]
    {:content (.getContent url)}))
