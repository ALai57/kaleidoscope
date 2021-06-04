(ns andrewslai.generators.files
  (:require [clojure.test.check.generators :as gen]))

(def gen-content-type
  (s/gen :s3.metadata/content-type))

(def gen-content-length
  (s/gen :s3.metadata/content-length))

(def gen-metadata
  (gen/hash-map :content-type gen-content-type
                :content-length gen-content-length))
