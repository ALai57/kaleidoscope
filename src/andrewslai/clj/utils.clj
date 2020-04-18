(ns andrewslai.clj.utils
  (:require [cheshire.core :as json]))

(defn parse-response-body [response]
  (-> response
      :body
      slurp
      (json/parse-string keyword)))

(defn parse-body [ring-map]
  (-> ring-map
      :body
      slurp
      (json/parse-string keyword)))

(defn body->map [body]
  (-> body
      slurp
      (json/parse-string keyword)))

(defn file->bytes [file]
  (with-open [xin (clojure.java.io/input-stream file)
              xout (java.io.ByteArrayOutputStream.)]
    (clojure.java.io/copy xin xout)
    (.toByteArray xout)))
