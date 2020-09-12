(ns andrewslai.clj.persistence)

(defprotocol Persistence
  (select  [this m])
  (transact! [this m]))
