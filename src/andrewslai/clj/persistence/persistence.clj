(ns andrewslai.clj.persistence.persistence)

(defprotocol Persistence
  (select  [this m])
  (transact! [this m]))
