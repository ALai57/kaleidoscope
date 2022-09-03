(ns andrewslai.clj.persistence.persistence)

;; TODO: Remove me? Seems like this isn't actually valuable
(defprotocol Persistence
  (select  [this m])
  (transact! [this m]))
