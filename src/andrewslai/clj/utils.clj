(ns andrewslai.clj.utils
  (:require [cheshire.core :as json]
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
