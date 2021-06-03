(ns andrewslai.generators.s3
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [clojure.test.check.generators :as gen]))

(def gen-bucket-name
  (s/gen :s3/bucket-name))

