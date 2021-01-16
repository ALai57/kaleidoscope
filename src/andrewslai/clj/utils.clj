(ns andrewslai.clj.utils
  (:require [cheshire.core :as json]
            [clojure.data.codec.base64 :as b64]
            [clojure.spec.alpha :as s]
            [slingshot.slingshot :refer [throw+]]))

(defn parse-body [ring-map]
  (-> ring-map
      :body
      slurp
      (json/parse-string keyword)))

(defn file->bytes [file]
  (with-open [xin (clojure.java.io/input-stream file)
              xout (java.io.ByteArrayOutputStream.)]
    (clojure.java.io/copy xin xout)
    (.toByteArray xout)))

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

(defn string->bytes [s]
  (->> s
       (map (comp byte int))
       byte-array))

(defn b64-encode [s]
  (->> s
       b64/encode
       String.))

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

(defn pg-conn []
  {:dbname   (System/getenv "ANDREWSLAI_DB_NAME")
   :db-port  (or (System/getenv "ANDREWSLAI_DB_PORT") "5432")
   :host     (System/getenv "ANDREWSLAI_DB_HOST")
   :user     (System/getenv "ANDREWSLAI_DB_USER")
   :password (System/getenv "ANDREWSLAI_DB_PASSWORD")
   :dbtype   "postgresql"})

(comment
  (System/getenv "ANDREWSLAI_DB_PORT")
  (pg-conn)
  )
