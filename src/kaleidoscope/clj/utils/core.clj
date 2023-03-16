(ns kaleidoscope.clj.utils.core
  (:require [clojure.string :as string])
  (:import java.util.UUID))

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

(defn now []
  (java.time.LocalDateTime/now))

(defn uuid []
  (java.util.UUID/randomUUID))
