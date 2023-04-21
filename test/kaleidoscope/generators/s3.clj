(ns kaleidoscope.generators.s3
  (:require [clojure.spec.alpha :as s]
            [clojure.test.check.generators :as gen]
            [kaleidoscope.generators.files :as gen-file]
            [kaleidoscope.generators.networking :as gen-net]))

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
