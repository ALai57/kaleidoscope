(ns andrewslai.clj.persistence.s3-test
  (:require [amazonica.aws.s3 :as s3]
            [amazonica.aws.s3transfer :as s3t]
            [amazonica.core :as aws]
            [andrewslai.clj.persistence.s3 :refer :all]
            [andrewslai.clj.persistence.filesystem :as fs]
            [andrewslai.clj.protocols.s3 :as s3p]
            [biiwide.sandboxica.alpha :as sandbox]
            [clojure.test :refer [are deftest is]]
            [ring.util.response :as response]
            [matcher-combinators.test])
  (:import [com.amazonaws.services.s3.model PutObjectRequest]))

(defn prop->name
  "Convert java object property of form `isXXX` to be more clojure-y `XXX?`"
  [prop]
  (if (.startsWith prop "is")
    (str (.substring prop 2) "?")
    prop))

(defn aws-coerceable?
  "Does amazonica know how to coerce this data type?"
  [x]
  (satisfies? aws/IMarshall x)
  #_(get @@#'aws/coercions (class x)))

(defn coerce-to-map
  "Coerce java object to clojure map.
  Used for data types that amazonica doesn't know how to coerce."
  [obj]
  (reduce-kv (fn [m k v]
               (conj m {(#'aws/camel->keyword (prop->name (name k)))
                        (#'aws/marshall v)}))
             {}
             (bean obj)))

(defn default-marshall
  [method args]
  (map (fn [x]
         (if (not (aws-coerceable? x))
           (coerce-to-map x)
           x))
       (aws/marshall (#'aws/prepare-args method args))))

(extend-protocol aws/IMarshall
  PutObjectRequest
  (marshall [obj]
    (coerce-to-map obj)))

(defn byte-array-input-stream?
  [obj]
  (= (class obj) java.io.ByteArrayInputStream))

(deftest put-object-test
  (let [bucket       "andrewslai-wedding"
        input-stream (java.io.ByteArrayInputStream. (.getBytes "<h1>HELLO</h1>"))
        metadata     {:content-type   "image/svg"
                      :content-length 110
                      :something      "some"}
        s3-key       "something"]
    (sandbox/with (comp (sandbox/just
                         (s3/put-object
                          ([req]
                           (or (is (match? {:key s3-key
                                            :input-stream byte-array-input-stream?
                                            :bucket-name  bucket
                                            :metadata     (prepare-metadata metadata)}
                                           req))
                               (throw (Exception. "Invalid inputs")))
                           {:bucket-name     bucket
                            :common-prefixes []
                            :key-count       1})))
                        sandbox/always-fail)
      (fs/put-file (map->S3 {:bucket bucket
                             :creds  {:profile "dummy"}})
                   s3-key
                   input-stream
                   metadata))))
