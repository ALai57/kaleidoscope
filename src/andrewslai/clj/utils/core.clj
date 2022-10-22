(ns andrewslai.clj.utils.core
  (:require [clojure.string :as string]))

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

(defn change-newlines
  "AWS Cloudwatch breaks up logs based on newline characters \n, which makes it
  horrible to read logs.
  This allows us to do multiline logging in Cloudwatch."
  [s]
  (string/replace s #"\n" "\r"))
