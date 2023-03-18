(ns kaleidoscope.generators.files
  (:require [kaleidoscope.generators.networking :as gen-net]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [clojure.test.check.generators :as gen]))

(def gen-content-type
  (s/gen :s3.metadata/content-type))

(def gen-content-length
  (s/gen :s3.metadata/content-length))

(def gen-metadata
  (gen/hash-map :content-type gen-content-type
                :content-length gen-content-length))

(def gen-path
  (gen/fmap (fn [xs] (string/join "/" xs))
            (gen/vector gen-net/gen-domain 1 10)))

(def gen-filename
  (gen/fmap (fn [xs] (string/join "." xs))
            (gen/vector gen/string-alphanumeric 2)))

(comment
  (gen/sample gen-filename)
  )
