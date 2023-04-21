(ns kaleidoscope.generators.s3
  (:require [kaleidoscope.persistence.filesystem.s3-impl]
            [kaleidoscope.generators.networking :as gen-net]
            [kaleidoscope.generators.files :as gen-file]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [clojure.test.check.generators :as gen]))

(def gen-bucket-name
  gen-net/gen-host)

(def gen-key
  (s/gen :s3/key))

(def gen-user-metadata
  (gen/map gen/string gen/simple-type-printable))

(def gen-metadata
  (gen/hash-map :content-type gen-file/gen-content-type
                :content-length gen-file/gen-content-length
                :user-metadata gen-user-metadata))
