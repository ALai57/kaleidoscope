(ns andrewslai.generators.s3
  (:require [andrewslai.clj.persistence.s3]
            [andrewslai.generators.networking :as gen-net]
            [andrewslai.generators.files :as gen-file]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [clojure.test.check.generators :as gen]))

(def gen-bucket-name
  gen-net/gen-host)

(def gen-key
  (s/gen :s3/key))

(def gen-user-metadata
  (gen/map gen/simple-type-printable gen/simple-type-printable))

(def gen-metadata
  (gen/hash-map :content-type gen-file/gen-content-type
                :content-length gen-file/gen-content-length
                :user-metadata gen-user-metadata))
