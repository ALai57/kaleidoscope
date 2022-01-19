(ns andrewslai.clj.utils.core
  (:require [cheshire.core :as json]
            [clojure.data.codec.base64 :as b64]
            [clojure.spec.alpha :as s]
            [slingshot.slingshot :refer [throw+]]))

(defn validate [type data ex]
  (if (s/valid? type data)
    true
    (throw+
     (let [reason (s/explain-str type data)]
       {:type ex
        :subtype type
        :message {:data data
                  :reason reason
                  :feedback (or (:feedback data)
                                reason)}}))))

(defn deep-merge
  [m1 m2]
  (cond
    (nil? m2) m1
    (and (map? m1)
         (map? m2)) (reduce (fn [acc k]
                              (assoc acc k (deep-merge (get m1 k)
                                                       (get m2 k))))
                            m1
                            (mapcat keys [m1 m2]))
    :else m2))

(defn find-first-match
  "Searches through a collection of potential matches to find the first matching
  regex and return the associated value."
  [potential-matches s default-val]
  (reduce (fn [default [regexp v]]
            (if (re-find regexp s)
              (reduced v)
              default))
          default-val
          potential-matches))
