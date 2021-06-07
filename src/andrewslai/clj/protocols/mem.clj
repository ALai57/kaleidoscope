(ns andrewslai.clj.protocols.mem
  (:require [andrewslai.clj.protocols.core :as protocols]
            [ring.util.response :as ring-response]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Install multimethod to get resource-data from URLs using MEM-PROTOCOL
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def MEM-PROTOCOL
  "In memory protocol"
  "mem")

(defmethod ring-response/resource-data (keyword MEM-PROTOCOL)
  [url]
  (let [conn (.openConnection url)]
    {:content (.getContent url)}))
